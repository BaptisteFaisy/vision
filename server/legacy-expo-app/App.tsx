import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StyleSheet } from 'react-native';
import { CameraScreen } from './src/screens/CameraScreen';
import { ResultScreen } from './src/screens/ResultScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { colors } from './src/theme';
import type { CapturedPhoto } from './src/types';
import { DEFAULT_API_URL, loadApiUrl } from './src/utils/config';

type Route = { name: 'camera' } | { name: 'result'; photo: CapturedPhoto } | { name: 'settings' };

export default function App() {
  const [route, setRoute] = useState<Route>({ name: 'camera' });
  const [returnRoute, setReturnRoute] = useState<Route>({ name: 'camera' });
  const [apiUrl, setApiUrl] = useState<string>(DEFAULT_API_URL);

  useEffect(() => {
    let alive = true;
    void loadApiUrl().then((stored) => {
      if (alive) setApiUrl(stored);
    });
    return () => {
      alive = false;
    };
  }, []);

  const openSettings = () => {
    if (route.name !== 'settings') setReturnRoute(route);
    setRoute({ name: 'settings' });
  };

  const goBack = () => setRoute(route.name === 'settings' ? returnRoute : { name: 'camera' });

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
        <StatusBar style="light" />
        {route.name === 'camera' ? (
          <CameraScreen onCaptured={(photo) => setRoute({ name: 'result', photo })} onOpenSettings={openSettings} />
        ) : null}
        {route.name === 'result' ? (
          <ResultScreen
            key={route.photo.uri}
            photo={route.photo}
            apiUrl={apiUrl}
            onNewPhoto={() => setRoute({ name: 'camera' })}
            onOpenSettings={openSettings}
          />
        ) : null}
        {route.name === 'settings' ? (
          <SettingsScreen apiUrl={apiUrl} onChange={setApiUrl} onBack={goBack} />
        ) : null}
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
});