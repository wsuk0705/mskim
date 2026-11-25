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
  button.setText("���ټ� �������� ������ �ѱۡ��� �Ѽ���.\nWi-Fi���� ���� ���� �غ��� �� ���̵������� ������.");
  button.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
  setContentView(button);
 }
}
