package it.andrea.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DEFAULT = "http://100.96.189.65:5000/";
    private WebView web;
    private boolean dialogo = false;
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsPronto = false;

    private SharedPreferences prefs() {
        return getSharedPreferences("jarvis", MODE_PRIVATE);
    }

    private void js(final String s) {
        runOnUiThread(() -> web.evaluateJavascript(s, null));
    }

    class Ponte {
        @JavascriptInterface
        public void imposta(String nome, String chiave) {
            prefs().edit().putString("nome", nome).putString("chiave", chiave).apply();
            avviaServizio();
        }

        @JavascriptInterface
        public void cancella() {
            prefs().edit().remove("nome").remove("chiave").apply();
            stopService(new Intent(MainActivity.this, JarvisService.class));
        }

        @JavascriptInterface
        public void ascolta() {
            runOnUiThread(() -> avviaAscolto());
        }

        @JavascriptInterface
        public void parla(final String testo) {
            runOnUiThread(() -> {
                if (ttsPronto) tts.speak(testo, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
            });
        }
    }

    private void avviaServizio() {
        try {
            startForegroundService(new Intent(this, JarvisService.class));
        } catch (Exception e) {
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#02060f"));
        getWindow().setNavigationBarColor(Color.parseColor("#02060f"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#02060f"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Ponte(), "JarvisNativo");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    errore();
                }
            }
        });
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.ITALIAN);
                ttsPronto = true;
            }
        });
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        String ind = prefs().getString("indirizzo", null);
        if (ind == null) {
            chiediIndirizzo(DEFAULT);
        } else {
            web.loadUrl(ind);
            if (prefs().getString("nome", null) != null && prefs().getString("chiave", null) != null) {
                avviaServizio();
            }
            chiediPermessi();
        }
    }

    @Override
    protected void onDestroy() {
        if (sr != null) sr.destroy();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 2 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            avviaAscolto();
        }
    }

    private void avviaAscolto() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("statoVoce('Riconoscimento vocale non disponibile su questo telefono')");
            return;
        }
        if (tts != null) tts.stop();
        if (sr != null) sr.destroy();
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) {
                js("statoVoce('Ti ascolto...')");
            }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {}
            public void onError(int e) {
                js("statoVoce('Non ho capito (errore " + e + ")')");
            }
            public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) {
                    js("vocePronta(" + JSONObject.quote(l.get(0)) + ")");
                }
            }
            public void onPartialResults(Bundle p) {}
            public void onEvent(int t, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        sr.startListening(i);
    }

    private void chiediPermessi() {
        if (!Settings.canDrawOverlays(this) && !prefs().getBoolean("overlay_chiesto", false)) {
            prefs().edit().putBoolean("overlay_chiesto", true).apply();
            new AlertDialog.Builder(this)
                .setTitle("Permesso per sveglie e timer")
                .setMessage("Per impostare sveglie e timer a comando, anche con lo schermo spento, Jarvis ha bisogno di Mostra sopra altre app. Nella pagina che si apre attiva l'interruttore per Jarvis.")
                .setPositiveButton("Apri impostazioni", (d, w) -> startActivity(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Più tardi", null)
                .setOnDismissListener(d -> chiediPermessi())
                .show();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs().getBoolean("batteria_chiesta", false)) {
            prefs().edit().putBoolean("batteria_chiesta", true).apply();
            new AlertDialog.Builder(this)
                .setTitle("Sempre attivo")
                .setMessage("Perché Jarvis resti attivo a schermo spento, consenti di ignorare le limitazioni della batteria. Nella schermata che si apre scegli Consenti.")
                .setPositiveButton("Continua", (d, w) -> startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Più tardi", null)
                .show();
        }
    }

    private void chiediIndirizzo(String attuale) {
        if (dialogo) return;
        dialogo = true;
        final EditText t = new EditText(this);
        t.setText(attuale);
        t.setSingleLine(true);
        new AlertDialog.Builder(this)
            .setTitle("Indirizzo del cervello di Jarvis")
            .setMessage("Esempio: http://100.96.189.65:5000/")
            .setView(t)
            .setCancelable(false)
            .setOnDismissListener(d -> dialogo = false)
            .setPositiveButton("Collega", (d, w) -> {
                String ind = t.getText().toString().trim();
                if (!ind.startsWith("http")) ind = "http://" + ind;
                if (!ind.endsWith("/")) ind = ind + "/";
                prefs().edit().putString("indirizzo", ind).apply();
                web.loadUrl(ind);
                chiediPermessi();
            })
            .show();
    }

    private void errore() {
        if (dialogo) return;
        dialogo = true;
        new AlertDialog.Builder(this)
            .setTitle("Cervello non raggiungibile")
            .setMessage("Controlla che Termux sia acceso sul telefono principale e che Tailscale sia connesso.")
            .setOnDismissListener(d -> dialogo = false)
            .setPositiveButton("Riprova", (d, w) -> web.loadUrl(prefs().getString("indirizzo", DEFAULT)))
            .setNegativeButton("Cambia indirizzo", (d, w) -> {
                dialogo = false;
                chiediIndirizzo(prefs().getString("indirizzo", DEFAULT));
            })
            .show();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
