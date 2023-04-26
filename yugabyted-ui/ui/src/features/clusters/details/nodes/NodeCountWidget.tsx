import React, { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { Grid, makeStyles, Typography } from '@material-ui/core';
import { YBStatus, STATUS_TYPES } from '@app/components';

// Local imports
import type { ClusterNodesResponse } from '@app/api/src';

const useStyles = makeStyles((theme) => ({
  clusterInfo: {
    paddingRight: theme.spacing(2),
    marginRight: theme.spacing(2),
    borderRight: `1px solid ${theme.palette.grey[300]}`
  },
  container: {
    justifyContent: 'start',
  },
  label: {
    color: theme.palette.grey[500],
    fontWeight: theme.typography.fontWeightMedium as number,
    marginBottom: theme.spacing(0.75),
    textTransform: 'uppercase',
    textAlign: 'left'
  },
  value: {
    color: theme.palette.grey[700],
    paddingTop: theme.spacing(0.57),
    textAlign: 'left'
  },
  valueText: {
    marginRight: theme.spacing(1)
  },
  valueIcon: {
    marginRight: theme.spacing(2)
  },
  warning: {
    color: theme.palette.warning[500]
  }
}));

interface NodeCountWidgetProps {
  nodes: ClusterNodesResponse | undefined;
  isLoadBalancerIdle: boolean | undefined;
  fetchingIsLoadBalancerIdle: boolean;
}

export const NodeCountWidget: FC<NodeCountWidgetProps> = ({
    nodes,
    isLoadBalancerIdle,
    fetchingIsLoadBalancerIdle
}) => {
  const classes = useStyles();
  const { t } = useTranslation();

  const nodesData = nodes?.data;

  const numNodes = nodesData ? nodesData.length : 0;
  const deadNodes =
    nodesData ? nodesData.filter(node => !node.is_node_up) : [];
  const bootstrappingNodes = nodesData
    ? nodesData.filter(node => {
        return fetchingIsLoadBalancerIdle
        ? false
        : !node.is_node_up || !node.is_master_up
        ? false
        : node.metrics.uptime_seconds < 60 && !isLoadBalancerIdle ||
          node.metrics.user_tablets_leaders + node.metrics.system_tablets_leaders == 0;
    })
    : [];
  const numHealthyNodes = numNodes - deadNodes.length;

  return (
      <Grid container className={classes.container}>
        <div className={classes.clusterInfo}>
          <Typography variant="body1" className={classes.label}>
            {t('clusterDetail.nodes.totalNodes')}
          </Typography>
          <Typography variant="h4" className={classes.value}>
            {numNodes}
          </Typography>
        </div>
        <div className={classes.clusterInfo}>
          <Typography variant="body1" className={classes.label}>
            {t('clusterDetail.nodes.nodeHealth')}
          </Typography>
          <Typography variant="h4" className={classes.value} style={{display: 'flex'}}>
            <div className={classes.valueText}>
                {numHealthyNodes}
            </div>
            <div className={classes.valueIcon}>
                <YBStatus value={numHealthyNodes} type={STATUS_TYPES.SUCCESS} tooltip />
            </div>
            <div className={classes.valueText}>
                {deadNodes.length !== 0 ? deadNodes.length : ''}
            </div>
            <div className={classes.valueIcon}>
                <YBStatus value={deadNodes.length} type={STATUS_TYPES.ERROR} tooltip />
            </div>
          </Typography>
        </div>
        <div>
          <Typography variant="body1" className={classes.label}>
            {t('clusterDetail.nodes.bootstrapping')}
          </Typography>
          <Typography variant="h4" className={classes.value} style={{display: 'flex'}}>
            <div className={classes.valueText}>
                {bootstrappingNodes.length}
            </div>
            <div className={classes.valueIcon}>
                <YBStatus value={bootstrappingNodes.length} type={STATUS_TYPES.IN_PROGRESS} tooltip />
            </div>
          </Typography>
        </div>
      </Grid>
  );
};
