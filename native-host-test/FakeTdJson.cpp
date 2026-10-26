// OFFLINE TEST ONLY. Intentionally fake: never bundle in an Android APK.
#include <string>
static std::string reply;
extern "C" int td_create_client_id() { return 17; }
extern "C" void td_send(int id, const char* data) {
  const std::string input = (id == 17 && data) ? std::string(data) : "";
  if (input == "__truncated_2__") reply = std::string("\xE2\x82", 2);
  else if (input == "__truncated_3__") reply = std::string("\xF0\x9F\x98", 3);
  else if (input == "__overlong__") reply = std::string("\xC0\xAF", 2);
  else if (input == "__surrogate__") reply = std::string("\xED\xA0\x80", 3);
  else if (input == "__above_max__") reply = std::string("\xF4\x90\x80\x80", 4);
  else reply = input;
}
extern "C" const char* td_receive(double) {
  return reply.empty() ? nullptr : reply.c_str();
}
