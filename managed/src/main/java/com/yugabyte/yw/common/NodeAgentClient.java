// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.common;

import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.inject.Inject;
import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import com.yugabyte.yw.common.certmgmt.CertificateHelper;
import com.yugabyte.yw.common.config.GlobalConfKeys;
import com.yugabyte.yw.common.config.ProviderConfKeys;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.models.NodeAgent;
import com.yugabyte.yw.models.NodeAgent.State;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.nodeagent.NodeAgentGrpc;
import com.yugabyte.yw.nodeagent.NodeAgentGrpc.NodeAgentBlockingStub;
import com.yugabyte.yw.nodeagent.NodeAgentGrpc.NodeAgentStub;
import com.yugabyte.yw.nodeagent.Server.DownloadFileRequest;
import com.yugabyte.yw.nodeagent.Server.DownloadFileResponse;
import com.yugabyte.yw.nodeagent.Server.ExecuteCommandRequest;
import com.yugabyte.yw.nodeagent.Server.ExecuteCommandResponse;
import com.yugabyte.yw.nodeagent.Server.FileInfo;
import com.yugabyte.yw.nodeagent.Server.PingRequest;
import com.yugabyte.yw.nodeagent.Server.PingResponse;
import com.yugabyte.yw.nodeagent.Server.UpdateRequest;
import com.yugabyte.yw.nodeagent.Server.UpdateResponse;
import com.yugabyte.yw.nodeagent.Server.UpgradeInfo;
import com.yugabyte.yw.nodeagent.Server.UploadFileRequest;
import com.yugabyte.yw.nodeagent.Server.UploadFileResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/** This class contains all the methods required to communicate to a node agent server. */
@Slf4j
@Singleton
public class NodeAgentClient {
  public static final String NODE_AGENT_CONNECT_TIMEOUT_PROPERTY = "yb.node_agent.connect_timeout";
  public static final String NODE_AGENT_CLIENT_ENABLED_PROPERTY = "yb.node_agent.client.enabled";
  public static final Duration IDLE_CONNECT_TIMEOUT = Duration.ofMinutes(20);
  public static final int FILE_UPLOAD_CHUNK_SIZE_BYTES = 4096;

  // Cache of the channels for re-use.
  private final LoadingCache<ChannelConfig, ManagedChannel> cachedChannels;

  private final Config appConfig;
  private final ChannelFactory channelFactory;

  private final RuntimeConfGetter confGetter;

  @Inject
  public NodeAgentClient(Config appConfig, RuntimeConfGetter confGetter) {
    this(appConfig, confGetter, null);
  }

  public NodeAgentClient(
      Config appConfig, RuntimeConfGetter confGetter, ChannelFactory channelFactory) {
    this.appConfig = appConfig;
    this.confGetter = confGetter;
    this.channelFactory =
        channelFactory == null
            ? config -> ChannelFactory.getDefaultChannel(config)
            : channelFactory;
    this.cachedChannels =
        CacheBuilder.newBuilder()
            .removalListener(
                n -> {
                  ManagedChannel channel = (ManagedChannel) n.getValue();
                  if (!channel.isShutdown() && !channel.isTerminated()) {
                    channel.shutdown();
                  }
                })
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .maximumSize(100)
            .build(
                new CacheLoader<ChannelConfig, ManagedChannel>() {
                  @Override
                  public ManagedChannel load(ChannelConfig config) {
                    return NodeAgentClient.this.channelFactory.get(config);
                  }
                });
  }

  @Builder
  public static class NodeAgentUpgradeParam {
    private String certDir;
    private Path packagePath;
  }

  /** This class intercepts the client request to add the authorization token header. */
  @Slf4j
  public static class GrpcClientRequestInterceptor implements ClientInterceptor {
    private final UUID nodeAgentUuid;

    GrpcClientRequestInterceptor(UUID nodeAgentUuid) {
      this.nodeAgentUuid = nodeAgentUuid;
    }

    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions, Channel channel) {

      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
          channel.newCall(methodDescriptor, callOptions)) {

        @Override
        public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
          log.trace("Setting authorizaton token in header for node agent {}", nodeAgentUuid);
          String token = NodeAgentClient.getNodeAgentJWT(nodeAgentUuid);
          headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), token);
          super.start(responseListener, headers);
        }
      };
    }
  }

  @FunctionalInterface
  public interface ChannelFactory {
    ManagedChannel get(ChannelConfig config);

    static ManagedChannel getDefaultChannel(ChannelConfig config) {
      NodeAgent nodeAgent = config.nodeAgent;
      NettyChannelBuilder channelBuilder =
          NettyChannelBuilder.forAddress(nodeAgent.getIp(), nodeAgent.getPort())
              .idleTimeout(config.getIdleTimeout().toMinutes(), TimeUnit.MINUTES);
      if (config.isEnableTls()) {
        try {
          String certPath = config.getCertPath().toString();
          log.debug("Using cert path {} for node agent {}", certPath, config.nodeAgent.getUuid());
          SslContext sslcontext =
              GrpcSslContexts.forClient()
                  .trustManager(CertificateHelper.getCertsFromFile(certPath))
                  .build();
          channelBuilder = channelBuilder.sslContext(sslcontext);
          channelBuilder.intercept(new GrpcClientRequestInterceptor(nodeAgent.getUuid()));
        } catch (SSLException e) {
          throw new RuntimeException("SSL context creation for gRPC client failed", e);
        }
      } else {
        channelBuilder = channelBuilder.usePlaintext();
      }
      if (config.getConnectTimeout() != null) {
        channelBuilder.withOption(
            ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectTimeout().toMillis());
      }
      return channelBuilder.build();
    }
  }

  @Getter
  @Builder
  public static class ChannelConfig {
    private NodeAgent nodeAgent;
    private boolean enableTls;
    private Path certPath;
    private Duration connectTimeout;
    private Duration idleTimeout;

    @Override
    public int hashCode() {
      return new HashCodeBuilder(17, 37)
          .append(nodeAgent.getUuid())
          .append(enableTls)
          .append(certPath)
          .append(connectTimeout)
          .append(idleTimeout)
          .toHashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      ChannelConfig other = (ChannelConfig) obj;
      if (!Objects.equals(nodeAgent.getUuid(), other.nodeAgent.getUuid())) {
        return false;
      }
      if (enableTls != other.enableTls) {
        return false;
      }
      if (!Objects.equals(certPath, other.certPath)) {
        return false;
      }
      if (!Objects.equals(connectTimeout, other.connectTimeout)) {
        return false;
      }
      if (!Objects.equals(idleTimeout, other.idleTimeout)) {
        return false;
      }
      return true;
    }
  }

  @Slf4j
  static class BaseResponseObserver<T> implements StreamObserver<T> {
    private final String id;
    private final CountDownLatch latch = new CountDownLatch(1);
    private Throwable throwable;

    BaseResponseObserver(String id) {
      this.id = id;
    }

    @Override
    public void onNext(T response) {}

    @Override
    public void onError(Throwable throwable) {
      latch.countDown();
      log.error("Error encountered for {}", getId(), throwable);
      this.throwable = throwable;
    }

    @Override
    public void onCompleted() {
      latch.countDown();
      log.info("Completed for {}", getId());
    }

    protected String getId() {
      return id;
    }

    public Throwable getThrowable() {
      return throwable;
    }

    public void waitFor() throws Throwable {
      try {
        latch.await();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      if (throwable != null) {
        throw throwable;
      }
    }
  }

  static class ExecuteCommandResponseObserver extends BaseResponseObserver<ExecuteCommandResponse> {
    private final StringBuilder stdOut;
    private final StringBuilder stdErr;

    ExecuteCommandResponseObserver(String id) {
      super(id);
      this.stdOut = new StringBuilder();
      this.stdErr = new StringBuilder();
    }

    @Override
    public void onNext(ExecuteCommandResponse response) {
      if (response.hasError()) {
        stdErr.append(response.getError().getMessage());
        onError(
            new RuntimeException(
                String.format(
                    "Error(%d) %s",
                    response.getError().getCode(), response.getError().getMessage())));
      } else {
        stdOut.append(response.getOutput());
      }
    }

    public String getStdOut() {
      return stdOut.toString();
    }

    public String getStdErr() {
      return stdErr.toString();
    }
  }

  static class DownloadFileResponseObserver extends BaseResponseObserver<DownloadFileResponse> {
    private final OutputStream outputStream;

    DownloadFileResponseObserver(String id, OutputStream outputStream) {
      super(id);
      this.outputStream = outputStream;
    }

    @Override
    public void onNext(DownloadFileResponse response) {
      try {
        outputStream.write(response.getChunkData().toByteArray());
      } catch (IOException e) {
        onError(e);
      }
    }
  }

  public static String getNodeAgentJWT(NodeAgent nodeAgent) {
    PrivateKey privateKey = nodeAgent.getPrivateKey();
    return Jwts.builder()
        .setIssuer("https://www.yugabyte.com")
        .setSubject("Platform")
        .setIssuedAt(new Date())
        .setExpiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
        .signWith(SignatureAlgorithm.RS512, privateKey)
        .compact();
  }

  public static String getNodeAgentJWT(UUID nodeAgentUuid) {
    Optional<NodeAgent> nodeAgentOp = NodeAgent.maybeGet(nodeAgentUuid);
    if (!nodeAgentOp.isPresent()) {
      throw new RuntimeException(String.format("Node agent %s does not exist", nodeAgentUuid));
    }
    return getNodeAgentJWT(nodeAgentOp.get());
  }

  public static void addNodeAgentClientParams(
      NodeAgent nodeAgent, List<String> cmdParams, Map<String, String> redactedVals) {
    String token = getNodeAgentJWT(nodeAgent);
    cmdParams.add("--node_agent_ip");
    cmdParams.add(nodeAgent.getIp());
    cmdParams.add("--node_agent_port");
    cmdParams.add(String.valueOf(nodeAgent.getPort()));
    cmdParams.add("--node_agent_cert_path");
    cmdParams.add(nodeAgent.getCaCertFilePath().toString());
    cmdParams.add("--node_agent_home");
    cmdParams.add(nodeAgent.getHome());
    cmdParams.add("--node_agent_auth_token");
    cmdParams.add(token);
    redactedVals.put(token, "REDACTED");
  }

  public Optional<NodeAgent> maybeGetNodeAgent(String ip, Provider provider) {
    if (isClientEnabled(provider)) {
      Optional<NodeAgent> optional = NodeAgent.maybeGetByIp(ip);
      if (optional.isPresent() && optional.get().getState() != State.REGISTERING) {
        return optional;
      }
    }
    return Optional.empty();
  }

  public boolean isClientEnabled(Provider provider) {
    return confGetter.getConfForScope(provider, ProviderConfKeys.enableNodeAgentClient);
  }

  public boolean isAnsibleOffloadingEnabled(Provider provider, String nodeAgentVersion) {
    String supportedVersion =
        confGetter.getGlobalConf(GlobalConfKeys.ansibleOffloadSupportedVersion);
    return isClientEnabled(provider)
        && confGetter.getConfForScope(provider, ProviderConfKeys.enableAnsibleOffloading)
        && Util.compareYbVersions(nodeAgentVersion, supportedVersion) >= 0;
  }

  private ManagedChannel getManagedChannel(NodeAgent nodeAgent, boolean enableTls) {
    Duration connectTimeout = appConfig.getDuration(NODE_AGENT_CONNECT_TIMEOUT_PROPERTY);
    ChannelConfig.ChannelConfigBuilder builder =
        ChannelConfig.builder()
            .nodeAgent(nodeAgent)
            .enableTls(enableTls)
            .connectTimeout(connectTimeout)
            .idleTimeout(IDLE_CONNECT_TIMEOUT);
    if (enableTls) {
      Path certPath = nodeAgent.getCaCertFilePath();
      if (nodeAgent.getState() == State.UPGRADE || nodeAgent.getState() == State.UPGRADED) {
        // Upgrade state is also considered just in case the DB update fails (very rare).
        // Node agent may still be running with old cert.
        Path mergedCertPath = nodeAgent.getMergedCaCertFilePath();
        if (mergedCertPath.toFile().exists()) {
          certPath = mergedCertPath;
        }
      }
      builder.certPath(certPath);
    }
    try {
      return cachedChannels.get(builder.build());
    } catch (ExecutionException e) {
      throw new RuntimeException(e.getCause());
    }
  }

  public PingResponse ping(NodeAgent nodeAgent) {
    return ping(nodeAgent, true);
  }

  public PingResponse ping(NodeAgent nodeAgent, boolean enableTls) {
    ManagedChannel channel = getManagedChannel(nodeAgent, enableTls);
    NodeAgentBlockingStub stub = NodeAgentGrpc.newBlockingStub(channel);
    return stub.ping(PingRequest.newBuilder().build());
  }

  public PingResponse waitForServerReady(NodeAgent nodeAgent, Duration timeout) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    while (true) {
      try {
        return ping(nodeAgent);
      } catch (RuntimeException e) {
        log.warn(
            "Error in validating connection to node agent {} - {}",
            nodeAgent.getIp(),
            e.getMessage());
        if (stopwatch.elapsed().compareTo(timeout) > 0) {
          throw e;
        }
        try {
          Thread.sleep(2000);
        } catch (InterruptedException ex) {
          throw new RuntimeException(ex);
        }
        log.info("Retrying connection validation to node agent {}", nodeAgent.getIp());
      }
    }
  }

  public String executeCommand(NodeAgent nodeAgent, List<String> command) {
    return executeCommand(nodeAgent, command, null);
  }

  public String executeCommand(NodeAgent nodeAgent, List<String> command, String user) {
    ManagedChannel channel = getManagedChannel(nodeAgent, true);
    NodeAgentStub stub = NodeAgentGrpc.newStub(channel);
    String id = String.format("%s-%s", nodeAgent.getUuid(), command.get(0));
    ExecuteCommandResponseObserver responseObserver = new ExecuteCommandResponseObserver(id);
    ExecuteCommandRequest.Builder builder =
        ExecuteCommandRequest.newBuilder().addAllCommand(command);
    if (StringUtils.isNotBlank(user)) {
      builder.setUser(user);
    }
    try {
      stub.executeCommand(builder.build(), responseObserver);
      responseObserver.waitFor();
      return responseObserver.getStdOut();
    } catch (Throwable e) {
      log.error("Error in running command. Error: {}", responseObserver.stdErr);
      throw new RuntimeException("Command execution failed. Error: " + e.getMessage(), e);
    }
  }

  public void uploadFile(NodeAgent nodeAgent, String inputFile, String outputFile) {
    uploadFile(nodeAgent, inputFile, outputFile, null, 0);
  }

  public void uploadFile(
      NodeAgent nodeAgent, String inputFile, String outputFile, String user, int chmod) {
    ManagedChannel channel = getManagedChannel(nodeAgent, true);
    try (InputStream inputStream = new BufferedInputStream(new FileInputStream(inputFile))) {
      NodeAgentStub stub = NodeAgentGrpc.newStub(channel);
      String id = String.format("%s-%s", nodeAgent.getUuid(), inputFile);
      BaseResponseObserver<UploadFileResponse> responseObserver = new BaseResponseObserver<>(id);
      StreamObserver<UploadFileRequest> requestObserver = stub.uploadFile(responseObserver);
      FileInfo fileInfo = FileInfo.newBuilder().setFilename(outputFile).build();
      UploadFileRequest.Builder builder = UploadFileRequest.newBuilder().setFileInfo(fileInfo);
      if (StringUtils.isNotBlank(user)) {
        builder.setUser(user);
      }
      if (chmod > 0) {
        builder.setChmod(chmod);
      }
      UploadFileRequest request = builder.build();
      // Send metadata first.
      requestObserver.onNext(builder.build());
      byte[] bytes = new byte[FILE_UPLOAD_CHUNK_SIZE_BYTES];
      int bytesRead = 0;
      while ((bytesRead = inputStream.read(bytes)) > 0) {
        request =
            UploadFileRequest.newBuilder()
                .setChunkData(ByteString.copyFrom(bytes, 0, bytesRead))
                .build();
        requestObserver.onNext(request);
      }
      requestObserver.onCompleted();
      responseObserver.waitFor();
    } catch (Throwable e) {
      throw new RuntimeException(
          String.format(
              "Error in uploading file %s to %s. Error: %s", inputFile, outputFile, e.getMessage()),
          e);
    }
  }

  public void downloadFile(NodeAgent nodeAgent, String inputFile, String outputFile) {
    downloadFile(nodeAgent, inputFile, outputFile, null);
  }

  public void downloadFile(NodeAgent nodeAgent, String inputFile, String outputFile, String user) {
    ManagedChannel channel = getManagedChannel(nodeAgent, true);
    try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
      NodeAgentStub stub = NodeAgentGrpc.newStub(channel);
      String id = String.format("%s-%s", nodeAgent.getUuid(), outputFile);
      DownloadFileResponseObserver responseObserver =
          new DownloadFileResponseObserver(id, outputStream);
      DownloadFileRequest.Builder builder = DownloadFileRequest.newBuilder().setFilename(inputFile);
      if (StringUtils.isNotBlank(user)) {
        builder.setUser(user);
      }
      stub.downloadFile(builder.build(), responseObserver);
      responseObserver.waitFor();
    } catch (Throwable e) {
      throw new RuntimeException(
          String.format(
              "Error in downloading file %s to %s. Error: %s",
              inputFile, outputFile, e.getMessage()),
          e);
    }
  }

  public void startUpgrade(NodeAgent nodeAgent, NodeAgentUpgradeParam param) {
    ManagedChannel channel = getManagedChannel(nodeAgent, true);
    NodeAgentBlockingStub stub = NodeAgentGrpc.newBlockingStub(channel);
    stub.update(
        UpdateRequest.newBuilder()
            .setState(State.UPGRADE.name())
            .setUpgradeInfo(
                UpgradeInfo.newBuilder()
                    .setPackagePath(param.packagePath.toString())
                    .setCertDir(param.certDir)
                    .build())
            .build());
  }

  public String finalizeUpgrade(NodeAgent nodeAgent) {
    ManagedChannel channel = getManagedChannel(nodeAgent, true);
    NodeAgentBlockingStub stub = NodeAgentGrpc.newBlockingStub(channel);
    UpdateResponse response =
        stub.update(UpdateRequest.newBuilder().setState(State.UPGRADED.name()).build());
    return response.getHome();
  }
}
