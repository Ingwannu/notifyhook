# NotifyHook ProGuard Rules
-keep class com.notifyhook.app.model.** { *; }
-keep class com.notifyhook.app.service.** { *; }

# 프리셋 파일 DTO(HookStorage 내부 클래스)는 Gson이 필드 이름을 그대로 JSON 키로 쓴다.
# 보존하지 않으면 R8이 필드를 a/b/c로 바꾸거나 상수로만 쓰이는 필드(format)를 지워,
# 릴리스 빌드가 내보낸 프리셋이 난독화된 키로 저장되고 다른 버전에서 가져올 수 없게 된다.
-keep class com.notifyhook.app.data.HookStorage$* {
    <fields>;
    <init>(...);
}

# Gson TypeToken(object : TypeToken<List<Hook>>() {})은 익명 하위 클래스의 제네릭 시그니처로 타입을 읽는다.
# R8 full mode(AGP 8 기본값)는 keep 규칙에 걸리지 않은 클래스의 Signature 속성을 지우므로,
# 아래 규칙이 없으면 릴리스 빌드가 저장소를 읽는 순간 "TypeToken must be created with a type argument"로 종료된다.
# Gson 2.11부터 라이브러리에 포함된 규칙과 같다(2.10.1에는 없음).
-keepattributes Signature
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
