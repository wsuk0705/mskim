package kr.map.hangul;
import android.app.*;
import com.google.mlkit.nl.translate.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
public class Start extends Activity {
 Button begin,end;
 TextView help;
 public void onCreate(Bundle state){
  setTheme(android.R.style.Theme_Material_Light_NoActionBar);
  super.onCreate(state);

  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);int pad=Math.round(20*getResources().getDisplayMetrics().density);
  layout.setPadding(pad,pad,pad,pad);scroll.addView(layout);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((view,insets)->{
   android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
   view.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;
  });scroll.requestApplyInsets();
  help=new TextView(this);help.setText("다른 앱 위에 표시를 허용한 후, 화면 공유에서 ‘앱 하나’와 AMap Global을 선택하세요. 처음에는 번역 모델을 다운로드합니다.");layout.addView(help);
  TextView modelStatus=new TextView(this);
  Button prepare=new Button(this);prepare.setText("오프라인 번역 준비 및 점검");layout.addView(prepare);layout.addView(modelStatus);
  prepare.setOnClickListener(v->{
   prepare.setEnabled(false);modelStatus.setText("모델 준비 중 · 최초 1회 인터넷 연결 필요");
   Translator test=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage("zh").setTargetLanguage("ko").build());
   test.downloadModelIfNeeded().onSuccessTask(unused->test.translate("地铁站在哪里")).addOnSuccessListener(value->modelStatus.setText("기기 내 번역 결과 · 地铁站在哪里 → "+value))
    .addOnFailureListener(e->modelStatus.setText("모델 준비 실패 · 인터넷 연결 후 다시 눌러 주세요. 기본 여행 단어는 오프라인 지원"))
    .addOnCompleteListener(t->{test.close();prepare.setEnabled(true);});
  });
  begin=new Button(this);begin.setText("AMap 화면 번역 시작");layout.addView(begin);
  begin.setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),1);else share();});
  end=new Button(this);end.setText("번역 종료");layout.addView(end);end.setOnClickListener(v->{stopService(new Intent(this,Reader.class));finish();});
 }
 protected void onResume(){super.onResume();boolean active=Reader.running;
  begin.setVisibility(active?android.view.View.GONE:android.view.View.VISIBLE);
  end.setVisibility(active?android.view.View.VISIBLE:android.view.View.GONE);
  help.setText(active?Reader.status:"아래 시작 버튼을 누른 뒤, 화면 공유에서 ‘앱 하나’와 AMap Global을 선택하세요. 처음에는 번역 모델을 다운로드합니다.");
 }
 void share(){startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),2);}
 protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
  if(req==1&&Settings.canDrawOverlays(this))share();
  if(req==2&&result==RESULT_OK&&data!=null){startForegroundService(new Intent(this,Reader.class).putExtra("grant",data));finish();}
 }
}