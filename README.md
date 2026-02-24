# robokassa-rn

Expo module для оплаты через Robokassa Android SDK.

Поддерживаемая платформа:
- Android only.

## Установка

```bash
npm install robokassa-rn
```

## Подключение в приложении (Expo)

Добавьте плагин в `app.json` или `app.config.ts`:

```json
{
  "expo": {
    "plugins": [
      [
        "robokassa-rn",
        {
          "returnUrls": [
            "https://pay.example.com/robokassa/success",
            "https://pay.example.com/robokassa/fail"
          ]
        }
      ]
    ]
  }
}
```

Затем выполните:

```bash
npx expo prebuild
```

После изменений в `plugins` снова запускайте `npx expo prebuild`.

## Быстрый пример оплаты

```ts
import RobokassaRn from 'robokassa-rn';
import { Platform, ToastAndroid } from 'react-native';

async function pay(currentUserId: string) {
  if (Platform.OS !== 'android') return;

  const result = await RobokassaRn.startPaymentAsync({
    merchantLogin: 'YOUR_MERCHANT_LOGIN',
    password1: 'YOUR_PASSWORD_1',
    password2: 'YOUR_PASSWORD_2',
    // invoiceId можно не передавать: Robokassa сгенерирует его автоматически
    // invoiceId: 12345,
    orderSum: 499.0,
    description: 'Оплата заказа #12345',
    email: 'user@example.com',
    culture: 'ru',
    toolbarText: 'Оплата заказа',
    extra: {
      userId: currentUserId,
      Shp_subscriptionType: 'premium'
    }
  });

  if (result.status === 'success') {
    ToastAndroid.show('Оплата успешна', ToastAndroid.SHORT);
    return;
  }

  if (result.status === 'cancelled') {
    ToastAndroid.show('Оплата отменена', ToastAndroid.SHORT);
    return;
  }

  ToastAndroid.show(result.errorDescription ?? 'Ошибка оплаты', ToastAndroid.LONG);
}
```

## Что означают поля оплаты

- `merchantLogin` — логин магазина из личного кабинета Robokassa.
- `password1` — пароль #1 из личного кабинета Robokassa.
- `password2` — пароль #2 из личного кабинета Robokassa.
- `invoiceId` — необязательный ID счета. Если передаете вручную, он должен быть уникальным для платежа.
- `orderSum` — сумма платежа.
- `description` — описание платежа.
- `email` — email покупателя.

## Пользовательские параметры (`extra`)

- Используйте `extra: Record<string, string>`.
- Ключи отправляются в Robokassa как `Shp_*`.
- Если ключ уже с префиксом `Shp_`, он будет отправлен как пользовательский параметр.
- Если ключ без префикса, модуль автоматически добавит `Shp_`.
- Параметры из `extra` включаются в `SignatureValue`.

Пример:

```ts
extra: {
  userId: '12345',            // -> Shp_userId=12345
  Shp_plan: 'premium'         // -> Shp_plan=premium
}
```

## Результат `startPaymentAsync`

- `success` — успешная оплата, может вернуть `invoiceId`.
- `cancelled` — пользователь отменил оплату.
- `error` — ошибка, вернет `errorCode` и/или `errorDescription`.

## Публикация обновлений в npm

1. Обновите версию пакета:

```bash
npm version patch
```

Для обычных релизов используйте `patch`, для новых фич `minor`, для ломающих изменений `major`.

2. Проверьте сборку перед публикацией:

```bash
npm run build
npm run lint
```

3. Авторизуйтесь в npm (если не авторизованы):

```bash
npm login
```

4. Опубликуйте пакет:

```bash
npm publish --access public
```

Если у аккаунта включен 2FA:

```bash
npm publish --access public --otp <код_из_приложения>
```

5. Отправьте изменения и тег версии в git:

```bash
git push
git push --tags
```

Примечание: во время `npm publish` автоматически сработает `prepublishOnly` из `package.json`.

## Важные замечания

- На iOS/web метод возвращает ошибку `unsupported platform`.
- Для `returnUrls` используйте реальные URL вашего backend.
- Если на странице оплаты видите ошибку авторизации, проверьте `merchantLogin/password1/password2` и режим магазина в Robokassa.
