package org.json;
import java.util.*;
public class JSONArray {
  final List<Object> data;
  public JSONArray() {data=new ArrayList<>();}
  JSONArray(List<Object> data) {this.data=data;}
  public JSONArray put(Object value) {data.add(value);return this;}
  public int length(){return data.size();}
  public JSONObject optJSONObject(int i) {return i>=0&&i<data.size()&&data.get(i) instanceof JSONObject ?(JSONObject)data.get(i):null;}
  public JSONObject getJSONObject(int i) {JSONObject a=optJSONObject(i);if(a==null)throw new JSONException("Not object");return a;}
  public String toString(){return JSONObject.format(data);}
}
