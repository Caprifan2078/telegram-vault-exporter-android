#include <jni.h>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <cstdint>
#include <cstring>

// Runtime-link against OFFICIAL libtdjson.so, installed into APK jniLibs for each ABI.
// Do not load or ship modified/unknown third-party Telegram libraries.
static void* library = nullptr;
static int (*create_client)() = nullptr;
static void (*send_request)(int, const char*) = nullptr;
static const char* (*receive_result)(double) = nullptr;
static const char* (*execute_sync)(const char*) = nullptr;
static std::once_flag init_once;

static bool ready() {
  std::call_once(init_once, [] {
    library = dlopen("libtdjson.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return;
    create_client = (int (*)())dlsym(library, "td_create_client_id");
    send_request = (void (*)(int, const char*))dlsym(library, "td_send");
    receive_result = (const char* (*)(double))dlsym(library, "td_receive");
    execute_sync = (const char* (*)(const char*))dlsym(library, "td_execute");
  });
  return library && create_client && send_request && receive_result && execute_sync;
}

// Execute synchronous TDLib operations before creating ANY client. In particular,
// prevent the verbose default logger from emitting private Telegram metadata.
// Do not print responses even on error.
static bool is_ok(const char* response) {
  return response && std::strstr(response, "\"@type\":\"ok\"") != nullptr;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_vault_exporter_TdNative_disableSensitiveLogging(JNIEnv*, jclass) {
  if (!ready()) return JNI_FALSE;
  // Reduce diagnostic verbosity and remove the log output stream; fail closed.
  if (!is_ok(execute_sync("{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":0}")))
    return JNI_FALSE;
  if (!is_ok(execute_sync("{\"@type\":\"setLogStream\",\"log_stream\":{\"@type\":\"logStreamEmpty\"}}")))
    return JNI_FALSE;
  return JNI_TRUE;
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_vault_exporter_TdNative_createClient(JNIEnv*, jclass) {
  if (!ready()) return 0;
  return create_client();
}
extern "C" JNIEXPORT void JNICALL
Java_org_vault_exporter_TdNative_send(JNIEnv* env, jclass, jlong client, jstring json) {
  if (!ready() || !json || client == 0) return;
  // GetStringUTFChars returns JNI *modified* UTF-8 (CESU-8 for emoji).
  // TDLib's JSON interface requires standard UTF-8. Convert UTF-16 ourselves
  // so supplementary Unicode characters are transmitted as 4-byte sequences.
  const jchar* chars = env->GetStringChars(json, nullptr);
  if (!chars) return;
  const jsize length = env->GetStringLength(json);
  std::string utf8;
  utf8.reserve(static_cast<size_t>(length) * 3u);
  for (jsize i = 0; i < length; ++i) {
    uint32_t c = static_cast<uint32_t>(chars[i]);
    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length &&
        chars[i+1] >= 0xDC00 && chars[i+1] <= 0xDFFF) {
      c = 0x10000u + ((c - 0xD800u) << 10) +
          (static_cast<uint32_t>(chars[++i]) - 0xDC00u);
    } else if (c >= 0xD800 && c <= 0xDFFF) {
      c = 0xFFFDu;
    }
    if (c < 0x80) utf8.push_back(static_cast<char>(c));
    else if (c < 0x800) {
      utf8.push_back(static_cast<char>(0xC0 | (c >> 6)));
      utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
    } else if (c < 0x10000) {
      utf8.push_back(static_cast<char>(0xE0 | (c >> 12)));
      utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
    } else {
      utf8.push_back(static_cast<char>(0xF0 | (c >> 18)));
      utf8.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
    }
  }
  env->ReleaseStringChars(json, chars);
  send_request(static_cast<int>(client), utf8.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_vault_exporter_TdNative_receive(JNIEnv* env, jclass, jdouble seconds) {
  if (!ready()) return nullptr;
  const char* response = receive_result(seconds);
  if (!response) return nullptr;
  // TDLib uses standard UTF-8, while JNI NewStringUTF expects modified UTF-8.
  // Convert explicitly to UTF-16 to preserve emoji and non-BMP code points.
  std::u16string decoded;
  // Never dereference p[i+1], p[i+2] or p[i+3] without verifying
  // the remaining UTF-8 input length; the old parser could read past NUL.
  const auto* p = reinterpret_cast<const unsigned char*>(response);
  const size_t length = std::strlen(response);
  for (size_t i = 0; i < length;) {
    const uint32_t lead = p[i];
    uint32_t c = 0xFFFDu;
    size_t consumed = 1;
    if (lead < 0x80u) {
      c = lead;
    } else if (lead >= 0xC2u && lead <= 0xDFu && length-i >= 2 &&
               (p[i+1] & 0xC0u) == 0x80u) {
      c = ((lead & 0x1Fu) << 6) | (p[i+1] & 0x3Fu);
      consumed = 2;
    } else if (lead >= 0xE0u && lead <= 0xEFu && length-i >= 3 &&
               (p[i+1] & 0xC0u) == 0x80u && (p[i+2] & 0xC0u) == 0x80u &&
               (lead != 0xE0u || p[i+1] >= 0xA0u) &&
               (lead != 0xEDu || p[i+1] < 0xA0u)) {
      c = ((lead & 0x0Fu) << 12) |
          ((p[i+1] & 0x3Fu) << 6) | (p[i+2] & 0x3Fu);
      consumed = 3;
    } else if (lead >= 0xF0u && lead <= 0xF4u && length-i >= 4 &&
               (p[i+1] & 0xC0u) == 0x80u && (p[i+2] & 0xC0u) == 0x80u &&
               (p[i+3] & 0xC0u) == 0x80u &&
               (lead != 0xF0u || p[i+1] >= 0x90u) &&
               (lead != 0xF4u || p[i+1] <= 0x8Fu)) {
      c = ((lead & 0x07u) << 18) |
          ((p[i+1] & 0x3Fu) << 12) |
          ((p[i+2] & 0x3Fu) << 6) | (p[i+3] & 0x3Fu);
      consumed = 4;
    }
    i += consumed;
    if (c <= 0xFFFFu) {
      decoded.push_back(static_cast<char16_t>(c));
    } else {
      c -= 0x10000u;
      decoded.push_back(static_cast<char16_t>(0xD800u | (c >> 10)));
      decoded.push_back(static_cast<char16_t>(0xDC00u | (c & 0x3FFu)));
    }
  }
  return env->NewString(reinterpret_cast<const jchar*>(decoded.data()), (jsize)decoded.size());
}
