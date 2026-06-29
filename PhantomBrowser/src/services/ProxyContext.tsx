import React, {createContext, useContext, useState, useEffect, useCallback} from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {NativeModules} from 'react-native';

const {ProxyBridgeModule} = NativeModules;

export interface ProxyConfig {
  server: string;
  port: number;
  username: string;
  password: string;
}

export interface ConnectionChain {
  localBridge: string;
  socks5Server: string;
  exitIP: string;
  latency: number;
  dnsMode: string;
}

export interface ProxyState {
  config: ProxyConfig | null;
  isConnected: boolean;
  isConnecting: boolean;
  chain: ConnectionChain | null;
  error: string | null;
  proxyUrl: string;
}

interface ProxyContextType extends ProxyState {
  connect: (cfg: ProxyConfig) => Promise<boolean>;
  disconnect: () => Promise<void>;
  saveConfig: (cfg: ProxyConfig) => Promise<void>;
  loadConfig: () => Promise<ProxyConfig | null>;
  recheckIP: () => Promise<void>;
}

const ProxyContext = createContext<ProxyContextType | null>(null);
const STORAGE_KEY = 'phantom_proxy_config';
const LOCAL_BRIDGE_PORT = 8118;

export function ProxyProvider({children}: {children: React.ReactNode}) {
  const [state, setState] = useState<ProxyState>({
    config: null,
    isConnected: false,
    isConnecting: false,
    chain: null,
    error: null,
    proxyUrl: '',
  });

  useEffect(() => {
    loadConfig().then(cfg => {
      if (cfg) setState(s => ({...s, config: cfg}));
    });
  }, []);

  const saveConfig = useCallback(async (cfg: ProxyConfig) => {
    await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(cfg));
    setState(s => ({...s, config: cfg}));
  }, []);

  const loadConfig = useCallback(async (): Promise<ProxyConfig | null> => {
    try {
      const raw = await AsyncStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      return JSON.parse(raw) as ProxyConfig;
    } catch {
      return null;
    }
  }, []);

  const connect = useCallback(async (cfg: ProxyConfig): Promise<boolean> => {
    setState(s => ({...s, isConnecting: true, error: null}));
    try {
      // Start native SOCKS5 bridge
      const result = await ProxyBridgeModule.startBridge(
        cfg.server,
        cfg.port,
        cfg.username,
        cfg.password,
        LOCAL_BRIDGE_PORT,
      );

      if (!result.success) {
        setState(s => ({...s, isConnecting: false, error: result.error}));
        return false;
      }

      const chain: ConnectionChain = {
        localBridge: `127.0.0.1:${LOCAL_BRIDGE_PORT}`,
        socks5Server: `${cfg.server}:${cfg.port}`,
        exitIP: result.exitIP,
        latency: result.latency,
        dnsMode: 'SOCKS5H (proxy-side DNS)',
      };

      await saveConfig(cfg);
      setState(s => ({
        ...s,
        config: cfg,
        isConnected: true,
        isConnecting: false,
        chain,
        proxyUrl: `http://127.0.0.1:${LOCAL_BRIDGE_PORT}`,
        error: null,
      }));
      return true;
    } catch (e: any) {
      setState(s => ({...s, isConnecting: false, error: e.message}));
      return false;
    }
  }, [saveConfig]);

  const disconnect = useCallback(async () => {
    try {
      await ProxyBridgeModule.stopBridge();
    } catch {}
    setState(s => ({
      ...s,
      isConnected: false,
      chain: null,
      proxyUrl: '',
    }));
  }, []);

  const recheckIP = useCallback(async () => {
    if (!state.isConnected || !state.config) return;
    try {
      const result = await ProxyBridgeModule.checkIP(
        state.config.server,
        state.config.port,
        state.config.username,
        state.config.password,
      );
      if (result.exitIP) {
        setState(s => ({
          ...s,
          chain: s.chain ? {...s.chain, exitIP: result.exitIP, latency: result.latency} : null,
        }));
      }
    } catch {}
  }, [state]);

  return (
    <ProxyContext.Provider
      value={{...state, connect, disconnect, saveConfig, loadConfig, recheckIP}}>
      {children}
    </ProxyContext.Provider>
  );
}

export function useProxy() {
  const ctx = useContext(ProxyContext);
  if (!ctx) throw new Error('useProxy must be used within ProxyProvider');
  return ctx;
}
