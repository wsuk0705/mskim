package kr.map.hangul;
import android.app.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
public class Start extends Activity {
 public void onCreate(Bundle state){
  super.onCreate(state);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,72,32,32);setContentView(layout);
  TextView help=new TextView(this);help.setText("다른 앱 위에 표시를 허용한 후, 화면 공유에서 ‘앱 하나’와 AMap Global을 선택하세요. 처음에는 번역 모델을 다운로드합니다.");layout.addView(help);
  Button begin=new Button(this);begin.setText("AMap 화면 번역 시작");layout.addView(begin);
  begin.setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),1);else share();});
  Button end=new Button(this);end.setText("번역 종료");layout.addView(end);end.setOnClickListener(v->stopService(new Intent(this,Reader.class)));
 }
 void share(){startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),2);}
 protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
  if(req==1&&Settings.canDrawOverlays(this))share();
  if(req==2&&result==RESULT_OK&&data!=null){startForegroundService(new Intent(this,Reader.class).putExtra("grant",data));finish();}
 }
}