import * as React from 'react';

import { RobokassaRnViewProps } from './RobokassaRn.types';

export default function RobokassaRnView(props: RobokassaRnViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
