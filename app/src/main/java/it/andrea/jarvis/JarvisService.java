package it.andrea.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class JarvisService extends Service {
    private volatile boolean attivo = false;
    private Thread filo;
    private MediaPlayer suono;
    private int volPrima = -1;
    private PowerManager.WakeLock wl;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("jarvis", "Jarvis attivo", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis attivo")
            .setContentText("In attesa dei comandi")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        if (!attivo) {
            attivo = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:servizio");
            wl.acquire();
            filo = new Thread(this::ciclo);
            filo.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        attivo = false;
        if (filo != null) filo.interrupt();
        fermaSuono();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    private void ciclo() {
        long ultimaPresenza = 0;
        while (attivo) {
            SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
            String ind = p.getString("indirizzo", null);
            String nome = p.getString("nome", null);
            String chiave = p.getString("chiave", null);
            if (ind != null && nome != null && chiave != null) {
                try {
                    if (System.currentTimeMillis() - ultimaPresenza > 20000) {
                        richiesta(ind + "api/dispositivi", "POST", chiave, "{\"nome\":" + JSONObject.quote(nome) + "}");
                        ultimaPresenza = System.currentTimeMillis();
                    }
                    String r = richiesta(ind + "api/comandi?nome=" + URLEncoder.encode(nome, "UTF-8"), "GET", chiave, null);
                    JSONArray a = new JSONObject(r).getJSONArray("comandi");
                    for (int i = 0; i < a.length(); i++) {
                        esegui(a.getJSONObject(i));
                    }
                } catch (Exception e) {
                    // rete assente o server spento: riprova al prossimo giro
                }
            }
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private String richiesta(String url, String metodo, String chiave, String corpo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(metodo);
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("X-Chiave", chiave);
        if (corpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(corpo.getBytes("UTF-8"));
            o.close();
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) {
            sb.append(l);
        }
        r.close();
        return sb.toString();
    }

    private void esegui(JSONObject c) {
        String tipo = c.optString("tipo");
        String arg = c.optString("arg");
        try {
            if (tipo.equals("torcia_on")) {
                torcia(true);
            } else if (tipo.equals("torcia_off")) {
                torcia(false);
            } else if (tipo.equals("suona")) {
                suona();
            } else if (tipo.equals("suona_stop")) {
                fermaSuono();
            } else if (tipo.equals("sveglia")) {
                sveglia(arg);
            } else if (tipo.equals("timer")) {
                timer(arg);
            }
        } catch (final Exception e) {
            ui.post(() -> Toast.makeText(this, "Jarvis, errore " + tipo + ": " + e, Toast.LENGTH_LONG).show());
        }
    }

    private void torcia(boolean on) throws Exception {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) {
                cm.setTorchMode(id, on);
                return;
            }
        }
    }

    private synchronized void suona() throws Exception {
        fermaSuono();
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        volPrima = am.getStreamVolume(AudioManager.STREAM_ALARM);
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        suono = new MediaPlayer();
        suono.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build());
        suono.setDataSource(this, u);
        suono.setLooping(true);
        suono.prepare();
        suono.start();
        ui.postDelayed(this::fermaSuono, 40000);
    }

    private synchronized void fermaSuono() {
        if (suono != null) {
            try {
                suono.stop();
            } catch (Exception e) {
            }
            suono.release();
            suono = null;
        }
        if (volPrima >= 0) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_ALARM, volPrima, 0);
            volPrima = -1;
        }
    }

    private void sveglia(String arg) {
        String[] p = arg.split(":");
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, Integer.parseInt(p[0]))
            .putExtra(AlarmClock.EXTRA_MINUTES, Integer.parseInt(p[1]))
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void timer(String arg) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, Integer.parseInt(arg))
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
