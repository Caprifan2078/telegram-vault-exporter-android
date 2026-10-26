package org.json;
import java.util.*;
/** Test-only JSON subset. NOT shipped in Android. Independent archive verifier parses JSON itself. */
public class JSONObject {
  final LinkedHashMap<String,Object> map;
  public JSONObject(){map=new LinkedHashMap<>();}
  JSONObject(Map<String,Object> map){this.map=new LinkedHashMap<>(map);}
  public JSONObject(String text){this();Parser p=new Parser(text);Object obj=p.parse();if(!(obj instanceof JSONObject j)||p.pos!=text.length())throw new JSONException("Invalid JSON object");map.putAll(j.map);}
  public JSONObject put(String key,Object value){map.put(key,value);return this;}
  public boolean has(String key){return map.containsKey(key);}
  public JSONObject optJSONObject(String key){Object v=map.get(key);return v instanceof JSONObject?(JSONObject)v:null;}
  public JSONArray optJSONArray(String key){Object v=map.get(key);return v instanceof JSONArray?(JSONArray)v:null;}
  public String optString(String key){return optString(key,"");}
  public String optString(String key,String fallback){Object v=map.get(key);return v==null?fallback:String.valueOf(v);}
  public boolean optBoolean(String key,boolean fallback){Object v=map.get(key);return v instanceof Boolean?(Boolean)v:fallback;}
  public int optInt(String key){return optInt(key,0);}
  public int optInt(String key,int fallback){Object v=map.get(key);return v instanceof Number?((Number)v).intValue():fallback;}
  public long optLong(String key){return optLong(key,0);}
  public long optLong(String key,long fallback){Object v=map.get(key);return v instanceof Number?((Number)v).longValue():fallback;}
  public long getLong(String key){if(!map.containsKey(key)||!(map.get(key) instanceof Number))throw new JSONException("Missing number: "+key);return ((Number)map.get(key)).longValue();}
  public int getInt(String key){return Math.toIntExact(getLong(key));}
  public String getString(String key){if(!map.containsKey(key))throw new JSONException("Missing: "+key);return String.valueOf(map.get(key));}
  @Override public String toString(){return format(map);}
  public String toString(int spaces){return toString();}
  static String format(Object value){
    if(value==null)return "null";
    if(value instanceof JSONObject j)return format(j.map);
    if(value instanceof JSONArray j)return format(j.data);
    if(value instanceof Map<?,?> m){StringBuilder b=new StringBuilder("{");boolean first=true;for(var e:m.entrySet()){if(!first)b.append(',');first=false;b.append(format(e.getKey().toString())).append(':').append(format(e.getValue()));}return b.append('}').toString();}
    if(value instanceof List<?> l){StringBuilder b=new StringBuilder("[");boolean first=true;for(Object v:l){if(!first)b.append(',');first=false;b.append(format(v));}return b.append(']').toString();}
    if(value instanceof String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
    return String.valueOf(value);
  }
  private static class Parser{
    final String s;int pos=0;
    Parser(String s){this.s=s;}
    void ws(){while(pos<s.length()&&Character.isWhitespace(s.charAt(pos)))pos++;}
    char ch(){if(pos>=s.length())throw new JSONException("Unexpected EOF");return s.charAt(pos);}
    void take(char c){ws();if(ch()!=c)throw new JSONException("Expected "+c+" at "+pos);pos++;}
    Object parse(){ws();Object v=value();ws();return v;}
    Object value(){ws();char c=ch();if(c=='{')return object();if(c=='[')return array();if(c=='"')return string();if(c=='t'&&s.startsWith("true",pos)){pos+=4;return true;}if(c=='f'&&s.startsWith("false",pos)){pos+=5;return false;}if(c=='n'&&s.startsWith("null",pos)){pos+=4;return null;}
      int st=pos;while(pos<s.length()&&"-0123456789.eE+".indexOf(s.charAt(pos))>=0)pos++;if(pos==st)throw new JSONException("Unexpected token at "+pos);
      try{String num=s.substring(st,pos);if(num.contains(".")||num.contains("e")||num.contains("E"))return Double.valueOf(num);return Long.valueOf(num);}catch(NumberFormatException x){throw new JSONException("Bad number",x);}
    }
    JSONObject object(){take('{');JSONObject j=new JSONObject();ws();if(ch()=='}'){pos++;return j;}while(true){String key=string();take(':');j.put(key,value());ws();if(ch()=='}'){pos++;return j;}take(',');} }
    JSONArray array(){take('[');JSONArray a=new JSONArray();ws();if(ch()==']'){pos++;return a;}while(true){a.put(value());ws();if(ch()==']'){pos++;return a;}take(',');} }
    String string(){take('"');StringBuilder b=new StringBuilder();while(true){char c=ch();pos++;if(c=='"')return b.toString();if(c=='\\'){char x=ch();pos++;switch(x){case 'n':b.append('\n');break;case 't':b.append('\t');break;case 'r':b.append('\r');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'u':if(pos+4>s.length())throw new JSONException("unicode escape");b.append((char)Integer.parseInt(s.substring(pos,pos+4),16));pos+=4;break;default:b.append(x);}}else b.append(c);} }
  }
}
