import { requireNativeView } from 'expo';
import * as React from 'react';

import { RobokassaRnViewProps } from './RobokassaRn.types';

const NativeView: React.ComponentType<RobokassaRnViewProps> =
  requireNativeView('RobokassaRn');

export default function RobokassaRnView(props: RobokassaRnViewProps) {
  return <NativeView {...props} />;
}
