# M:YOU - Personalized Mobility & Wellness Platform

**운전자 맞춤형 탑승 환경과 스마트 휴식모드를 제공하는 개인화 모빌리티 서비스**

- 디지털 키로 다가가면 운전자를 알아보고 문을 열어 주며, 시트를 타기 쉽게 물렸다가 시동을 켜면 운전자별 맞춤 자세로 맞춥니다.
- 졸음쉼터·휴게소에 도착해 기어 P와 주차 브레이크를 걸면 휴식모드를 제안하고, 무중력 자세와 공조·조명으로 차 안을 휴식 공간으로 바꿉니다.
- 휴식이 끝나면 차량 화면, 음성 안내, 스마트폰 알림으로 알리고, '주행 복귀'를 누르면 시트·공조·조명을 휴식 전 상태로 되돌립니다.

## APK

| 파일 | 설명 |
|---|---|
| [`apk/average183_MYOU_release.apk`](apk/average183_MYOU_release.apk) | 차량 앱 (Pleos Connect, Android Automotive OS) |
| [`apk/average183_MYOU-Phone_release.apk`](apk/average183_MYOU-Phone_release.apk) | 스마트폰 앱 (운전자 프로필·휴식 설정 관리, 차량 상태 알림) |

두 APK 모두 서명된 release 빌드입니다. 공모전 심사가 에뮬레이터에서 진행되므로 에뮬레이터 시연 기능(아래 "시연 기능" 참고)을 켠 상태로 빌드했습니다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 차량 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (Android Automotive OS, API 34), 화면 **2560 x 1440** |
| 스마트폰 에뮬레이터 | Android 에뮬레이터 API 36, 화면 1080 x 2400 |
| 개발 도구 | Android Studio, Kotlin, Jetpack Compose, Gradle 9.6 (wrapper 포함), JDK 17 |
| 최소 지원 버전 | Android API 28 |
| Pleos SDK | Vehicle 2.3.2, FusedLocation 1.1.3, NaviHelper 3.0.5, TextToSpeech 3.3.1 |

실제 차량은 필요하지 않습니다. API 키 등 별도 비밀값도 필요하지 않습니다.

## 실행 방법

### 1. 설치

차량 에뮬레이터와 스마트폰 에뮬레이터를 같은 PC에서 켠 뒤 설치합니다. `-g` 는 위치·알림 권한을 미리 허용합니다.

```bash
adb devices                                   # 에뮬레이터 serial 확인 (예: emulator-5554, emulator-5556)
adb -s <차량 serial> install -r -g apk/average183_MYOU_release.apk
adb -s <폰 serial>  install -r -g apk/average183_MYOU-Phone_release.apk
```

### 2. 차량 조작 패널 (선택, 시연을 편하게)

Virtual Console 의 기어·주차 브레이크·시동·GPS 조작을 한 화면에 모은 패널입니다. 폰 동기화 포트 연결도 함께 해 줍니다.

```bash
python3 car-app/tools/test-panel/serve.py     # → http://127.0.0.1:8090
```

패널을 쓰지 않을 때는 폰 동기화를 위해 포트만 연결합니다.

```bash
adb -s <차량 serial> forward tcp:9000 tcp:9000
```

### 3. 시나리오

**시나리오 1. 차량 접근과 맞춤 탑승**
1. 차량 앱의 '운전자 프리셋'에서 시트 자세를 저장하고, 디지털 키(예: 스마트폰 키 2)를 연결합니다.
2. 기어 P 상태에서 '디지털 키 접근(시연)'의 해당 키 `접근` 을 누릅니다. 문 잠금이 풀리고 시트가 뒤로 물러납니다(이지 액세스).
3. `시동 버튼(테스트)` 을 누르면 시트가 운전자 맞춤 자세로 이동합니다.

**시나리오 2. 졸음쉼터 도착과 휴식모드**
1. 패널의 `졸음쉼터 도착` 으로 위치를 쉼터 안으로 옮깁니다. (에뮬레이터 기본 위치가 오창 졸음쉼터 안이면 생략)
2. 기어 P에서 `주차 브레이크 걸기` 를 누르면 차량 앱에 휴식모드 제안이 뜹니다.
3. `휴식모드 시작` 을 누르면 시트가 휴식(무중력) 자세로 바뀌고 실내 온도·풍량·무드등이 바뀝니다. 휴식 시간은 1분으로 줄여 시험할 수 있습니다.
4. 시간이 끝나면 차량 알림과 음성 안내, 스마트폰 알림이 옵니다.

**시나리오 3. 주행 복귀**
1. `주행 복귀` 를 누르면 시트·공조·조명이 휴식 전 상태로 돌아갑니다.

**스마트폰 연동**
1. 스마트폰 앱을 열면 '내 차량'에 차량 상태(시동·운전자·휴식 남은 시간)가 표시됩니다.
2. 프로필을 만들거나 휴식 설정을 바꾼 뒤 `차량으로 보내기`, 차량 쪽 설정은 `차량에서 가져오기` 로 주고받습니다.
3. 앱을 홈으로 내려도 차량과 연결된 동안 상태를 확인해 휴식 종료 알림을 받습니다.

## 소스 코드 구조

```
car-app/     차량 앱 (Pleos Connect)
  app/src/main/java/com/myou/car/
    seat/      시트 프리셋 저장·적용 (Vehicle SDK Seat, DataStore)
    door/      도어 잠금 해제 (Vehicle SDK Door)
    key/       디지털 키 접근·인증 (시연용 시뮬레이션)
    drive/     기어(Vehicle SDK Gear), 시동·주차 브레이크(android.car), 경로 안내(NaviHelper SDK)
    location/  차량 위치 (FusedLocation SDK)
    rest/      휴게소 판정, 휴식 단계, 휴식 종료 알림·음성(TextToSpeech SDK)
    cabin/     공조·조명 설정과 복원 (Vehicle SDK HVAC, Light)
    sync/      스마트폰 앱과 동기화 (시연용)
    ui/        화면과 ViewModel
  app/src/main/assets/rest_areas.json   졸음쉼터·휴게소 위치
  tools/test-panel/                     에뮬레이터 차량 조작 패널
  tools/rest-areas/                     졸음쉼터 데이터 변환 스크립트
phone-app/   스마트폰 앱
apk/         release APK
```

## 직접 빌드하기

```bash
cd car-app   && ./gradlew :app:assembleDebug     # 또는 assembleRelease
cd phone-app && ./gradlew :app:assembleDebug
```

Pleos SDK 는 `settings.gradle.kts` 에 등록된 Pleos Playground Maven 저장소에서 받습니다.
release 서명을 하려면 각 프로젝트 루트에 `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword)를 두면 됩니다. 이 파일과 서명 키는 저장소에 포함하지 않습니다.
차량 앱 release 빌드는 R8 최적화를 끕니다. Pleos Vehicle SDK 가 실행 중에 Kotlin 어노테이션을 리플렉션으로 읽어, 최적화하면 앱이 바로 종료되기 때문입니다.

## 참고 사항

- **시연 기능** (`BuildConfig.DEMO_FEATURES`, 기본 켜짐)
  - Virtual Console 시트 연동: 에뮬레이터 v2.1.0 에서 Vehicle SDK 가 시트 위치를 항상 0으로 돌려주는 문제가 있어, 에뮬레이터 안의 Virtual Console(127.0.0.1:8088)에서 시트 값을 직접 읽고 움직입니다. 실제 차량에는 콘솔이 없어 자동으로 Vehicle SDK 를 씁니다.
  - 테스트 시동 버튼: 에뮬레이터 v2.1.0 이 시동 상태(IGNITION_STATE)를 지원하지 않아 Virtual Console 전원 상태를 씁니다.
  - 스마트폰 동기화: 차량 앱이 127.0.0.1:9000 에서 받고, 스마트폰 에뮬레이터는 `adb forward` 로 연결된 10.0.2.2:9000 으로 보냅니다. 인증이 없는 시연용 연결이므로 실제 서비스에서는 서버(FCM) 또는 블루투스 동기화로 바꿉니다.
- **사용 API**: 시동 상태와 주차 브레이크는 공개된 Pleos Vehicle SDK(2.3.2)에 조회 API가 없어 Android Automotive OS 표준 차량 API(`android.car`)로 읽습니다. 그 밖의 차량 조회·제어는 Pleos SDK 를 씁니다.
- **디지털 키**: 공개 SDK 에 디지털 키 인증 인터페이스가 없어 접근·인증을 시뮬레이션합니다. 현대차 디지털 키 시스템 연동 협의 후 `DigitalKeyGateway` 를 구현해 교체합니다.
- **데이터 출처**: 졸음쉼터 253곳은 공공데이터포털 '한국도로공사_졸음쉼터'(2026-02-25 기준) 데이터입니다. 휴게소 3곳은 시연용 좌표입니다.
