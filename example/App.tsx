import RobokassaRn from 'robokassa-rn';
import { Button, Platform, SafeAreaView, Text, ToastAndroid, View } from 'react-native';

export default function App() {
  const sdkAvailable = RobokassaRn.isRobokassaSdkAvailable();

  const showToast = (message: string, duration: number = ToastAndroid.SHORT) => {
    if (Platform.OS === 'android') {
      ToastAndroid.show(message, duration);
    }
  };

  const startPayment = async () => {
    if (Platform.OS !== 'android') {
      return;
    }

    if (!sdkAvailable) {
      showToast('Robokassa SDK не найден', ToastAndroid.LONG);
      return;
    }

    try {
      const result = await RobokassaRn.startPaymentAsync({
        merchantLogin: 'demo',
        password1: 'password1',
        password2: 'password2',
        invoiceId: 12345,
        orderSum: 8.96,
        description: 'Тестовый платеж',
        email: 'test@mail.ru',
        culture: 'ru',
        extra: {
          Shp_userId: '12345',
          Shp_subscriptionType: 'premium',
        },
      });

      switch (result.status) {
        case 'success':
          showToast(`Оплата успешна #${result.invoiceId ?? ''}`);
          break;
        case 'cancelled':
          showToast('Оплата отменена');
          break;
        case 'error':
          showToast(result.errorDescription ?? 'Ошибка оплаты', ToastAndroid.LONG);
          break;
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Неизвестная ошибка';
      showToast(message, ToastAndroid.LONG);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        <Text style={styles.header}>Robokassa Expo Module</Text>
        <Text style={styles.text}>
          SDK status: {sdkAvailable ? 'available' : 'missing'}
        </Text>
        <Button title="Запустить оплату" onPress={startPayment} />
      </View>
    </SafeAreaView>
  );
}

const styles = {
  content: {
    flex: 1,
    justifyContent: 'center' as const,
    paddingHorizontal: 24,
    gap: 12,
  },
  text: {
    fontSize: 16,
  },
  header: {
    fontSize: 28,
  },
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
};
