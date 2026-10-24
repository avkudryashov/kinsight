# Блокировка приложений на эмуляторе (без Bluetooth)

Сопряжение по Bluetooth на эмуляторе невозможно, поэтому debug-сборка умеет сама собрать фальшивую семью:
`DEMO_FAMILY_CHILD` и `DEMO_FAMILY_PARENT` в `app/src/debug/.../DebugGroupReceiver.kt` (логика в `DemoFamily.kt`).
Второй участник семьи существует только в памяти на время команды; его записи кладутся в базу как принятые синхронизацией.
Эмуляторы между собой не синхронизируются: «ребёнок» и «родитель» — две независимые семьи.

## 1. Два AVD

```sh
$ANDROID_HOME/emulator/emulator -list-avds
$ANDROID_HOME/emulator/emulator -avd <child_avd>  -port 5554 &
$ANDROID_HOME/emulator/emulator -avd <parent_avd> -port 5556 &
adb devices   # emulator-5554 (ребёнок), emulator-5556 (родитель)
```

Нужен образ с Google APIs, API 31+.

## 2. Debug APK

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug --console=plain -q
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
```

Онбординг не проходите и группу не создавайте: команда отказывает, если группа уже есть.
Сброс: `adb shell pm clear app.kinsight`.

## 3. Команды

У приёмника нет intent-filter, поэтому `-n` обязателен.

```sh
R=app.kinsight/.debug.DebugGroupReceiver; A=app.kinsight.debug

# Ребёнок: группа «Mom» (админ), PIN 1234, правила Mom, согласие и шаринг включены.
adb -s emulator-5554 shell am broadcast -n $R -a $A.DEMO_FAMILY_CHILD --es block com.android.chrome --ei total_min 2
# Без extras: block com.android.chrome, allow com.google.android.dialer, total 15 мин.
# Ещё: --es block_category GAMES,SOCIAL  --es allow com.a,com.b  --es limit com.google.android.youtube=30,com.x=45

# Родитель «Mom» с ребёнком «Kid»: 14 дней демо-статистики, SHARING_RESUMED,
# правила: BLOCK com.roblox.client, LIMIT com.google.android.youtube 30 мин (с блокировкой), ALLOW com.duolingo.
adb -s emulator-5556 shell am broadcast -n $R -a $A.DEMO_FAMILY_PARENT
adb -s emulator-5556 shell am broadcast -n $R -a $A.DEMO_FAMILY_PARENT --ez blocking_off true   # + открытый BLOCKING_OFF
```

Ответ (`result=0`, data) — одна строка с hex группы, ребёнка и числом правил. Ошибка — `result=1`, `error: ...`.

Лимиты кратны 15 минутам (от 15 мин до 12 ч, так требует `AppRuleRules`): `total_min 2` и `limit pkg=1` дают 15 минут.
Чтобы быстро упереться в лимит, используйте устройство 15+ минут или проверяйте BLOCK.

## 4. Служба специальных возможностей и доступ к статистике (ребёнок)

```sh
adb -s emulator-5554 shell appops set app.kinsight GET_USAGE_STATS allow
adb -s emulator-5554 shell settings put secure enabled_accessibility_services app.kinsight/app.kinsight.core.blocking.BlockingAccessibilityService
adb -s emulator-5554 shell settings put secure accessibility_enabled 1
```

Выключить: `settings put secure enabled_accessibility_services ""` и `accessibility_enabled 0`.

## 5. Что смотреть

Ребёнок (emulator-5554):
- Chrome (или пакет из `block`) закрывается и показывается экран блокировки; PIN `1234` открывает его до конца дня
  (в базе появится `BLOCK_OVERRIDDEN`).
- Приложение из `allow` (звонилка) открывается всегда, даже после общего лимита.
- После общего лимита блокируется всё, кроме разрешённого и системного.
- Служба выключена при действующих правилах: ребёнок подписывает `BLOCKING_OFF` и показывает предупреждение настройки.
- `adb shell am broadcast -n $R -a $A.STATUS` — `role=CHILD`, сдвиги `vector`.

Родитель (emulator-5556):
- В списке семьи ребёнок «Kid» со статистикой за 14 дней.
- На экране правил Kid: блок Roblox, лимит YouTube 30 мин, разрешён Duolingo; правила можно менять.
- С `blocking_off=true` — предупреждение «блокировка выключена» у Kid.

Логи: `adb logcat -s KinsightDebug` (только код и длина ответа, без данных).
