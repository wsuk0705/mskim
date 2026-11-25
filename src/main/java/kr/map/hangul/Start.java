package kr.map.hangul;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Button;
public class Start extends Activity {
 public void onCreate(Bundle state) {
  super.onCreate(state);
  Button button=new Button(this);
  button.setText("접근성 설정에서 ‘지도 한글’을 켜세요.\nWi-Fi에서 번역 모델을 준비한 뒤 바이두지도를 여세요.");
  button.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
  setContentView(button);
 }
}
