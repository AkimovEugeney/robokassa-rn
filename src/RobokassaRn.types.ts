import type { StyleProp, ViewStyle } from 'react-native';

export type OnLoadEventPayload = {
  url: string;
};

export type RobokassaCulture = 'ru' | 'en';

export type RobokassaPaymentOptions = {
  merchantLogin: string;
  password1: string;
  password2: string;
  invoiceId?: number;
  orderSum: number;
  description: string;
  email?: string;
  culture?: RobokassaCulture;
  isRecurrent?: boolean;
  isHold?: boolean;
  toolbarText?: string;
  previousInvoiceId?: number;
  token?: string;
  extra?: Record<string, string>;
};

export type RobokassaPaymentResult =
  | {
      status: 'success';
      invoiceId?: number;
    }
  | {
      status: 'cancelled';
    }
  | {
      status: 'error';
      errorCode?: string;
      errorDescription?: string;
    };

export type RobokassaRnModuleEvents = {
};

export type RobokassaRnViewProps = {
  url: string;
  onLoad: (event: { nativeEvent: OnLoadEventPayload }) => void;
  style?: StyleProp<ViewStyle>;
};
