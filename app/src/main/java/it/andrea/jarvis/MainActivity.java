package it.andrea.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

public class MainActivity extends Activity {
    private static final String DEFAULT = "http://100.96.189.65:5000/";
    private WebView web;
    private boolean dialogo = false;

    private SharedPreferences prefs() {
        return getSharedPreferences("jarvis", MODE_PRIVATE);
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
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    errore();
                }
            }
        });
        String ind = prefs().getString("indirizzo", null);
        if (ind == null) {
            chiediIndirizzo(DEFAULT);
        } else {
            web.loadUrl(ind);
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
