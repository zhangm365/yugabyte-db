import { connect } from 'react-redux';
import { toast } from 'react-toastify';

import {
  fetchRunTimeConfigs,
  fetchRunTimeConfigsResponse,
  setRunTimeConfig,
  setRunTimeConfigResponse,
  deleteRunTimeConfig,
  deleteRunTimeConfigResponse,
  resetRuntimeConfigs,
  DEFAULT_RUNTIME_GLOBAL_SCOPE
} from '../../../actions/customers';
import { RuntimeConfig } from './RuntimeConfig';

const TOAST_AUTO_CLOSE_INTERVAL = 2000; //ms

const mapDispatchToProps = (dispatch: any) => {
  return {
    fetchRuntimeConfigs: (scope: string = DEFAULT_RUNTIME_GLOBAL_SCOPE) => {
      dispatch(fetchRunTimeConfigs(scope, true)).then((response: any) =>
        dispatch(fetchRunTimeConfigsResponse(response.payload))
      );
    },
    setRuntimeConfig: (
      key: string,
      value: string,
      scope: string = DEFAULT_RUNTIME_GLOBAL_SCOPE
    ) => {
      dispatch(
        setRunTimeConfig({
          key: key,
          value: value,
          scope
        })
      ).then((response: any) => {
        try {
          if (response.payload.isAxiosError || response.payload.status !== 200) {
            const responseError = response.payload?.response?.data?.error;
            const errorMessage =
              typeof responseError !== 'string'
                ? 'Could not save config, try again'
                : responseError;
            toast.error(errorMessage, {
              autoClose: TOAST_AUTO_CLOSE_INTERVAL
            });
          } else {
            toast.success('Config saved successfully', {
              autoClose: TOAST_AUTO_CLOSE_INTERVAL
            });
            dispatch(setRunTimeConfigResponse(response.payload));
          }
        } catch (error) {
          console.error('Error while trying to save config');
        } finally {
          dispatch(fetchRunTimeConfigs(scope, true)).then((response: any) =>
            dispatch(fetchRunTimeConfigsResponse(response.payload))
          );
        }
      });
    },
    deleteRunTimeConfig: (key: string, scope: string = DEFAULT_RUNTIME_GLOBAL_SCOPE) => {
      dispatch(
        deleteRunTimeConfig({
          key: key,
          scope
        })
      ).then((response: any) => {
        try {
          if (response.payload.isAxiosError || response.payload.status !== 200) {
            toast.error('Could not delete config, try again', {
              autoClose: TOAST_AUTO_CLOSE_INTERVAL
            });
          } else {
            toast.success('Config resetted to parent scope successfully', {
              autoClose: TOAST_AUTO_CLOSE_INTERVAL
            });
            dispatch(deleteRunTimeConfigResponse(response.payload));
          }
        } catch (error) {
          console.error('Error while trying to save config');
        } finally {
          dispatch(fetchRunTimeConfigs(scope, true)).then((response: any) =>
            dispatch(fetchRunTimeConfigsResponse(response.payload))
          );
        }
      });
    },
    resetRuntimeConfigs: () => {
      dispatch(resetRuntimeConfigs());
    }
  };
};

export const RuntimeConfigContainer = connect(null, mapDispatchToProps)(RuntimeConfig);
