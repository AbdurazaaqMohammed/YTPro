package com.google.android.youtube.pro;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class MainActivity extends Activity {

    WebView web;
    private boolean portrait = false;
    BroadcastReceiver broadcastReceiver;

    private static class getQualitiesAsyncTask extends AsyncTask<Uri, Void, Void> {
        // https://greasyfork.org/en/scripts/497849-youtube-cobalt-tools-download-button/code
        private static String videoTitle;
        public static void fetchVideoQualities(QualitiesCallback callback, Uri uri) {
            MainActivity activity = activityReference.get();
            try {
                URL url = new URL(uri.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    boolean titleFound = false;
                    boolean qualityLabelFound = false;

                    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) {
                            if (!titleFound && inputLine.contains("<title>")) {
                                titleFound = true;
                                videoTitle = extractTitle(inputLine);
                            }
                            if (!qualityLabelFound && inputLine.contains("\"qualityLabel\"")) {
                                qualityLabelFound = true;
                                List<String> videoQualities = extractQualities(inputLine);
                                List<String> strippedQualities = stripQualityLabels(videoQualities);
                                List<String> filteredQualities = filterAndRemoveDuplicates(strippedQualities);

                                callback.onQualitiesFetched(filteredQualities);
                            }

                            if (titleFound && qualityLabelFound) {
                                break;
                            }
                        }
                    }
                } else {
                    activity.showError(new Exception("Failed to fetch video qualities. Status: " + responseCode), activity);
                    callback.onQualitiesFetched(new ArrayList<>()); // Empty list on failure
                }
            } catch (IOException e) {
                activity.showError(e, activity);
                callback.onQualitiesFetched(new ArrayList<>()); // Empty list on error
            }
        }
        private static String extractTitle(String html) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<title>(.*?)</title>");
            java.util.regex.Matcher matcher = pattern.matcher(html);
            return matcher.find() ? matcher.group(1) : "title_not_found";
        }

        // Function to extract video qualities from the HTML response
        private static List<String> extractQualities(String html) {
            List<String> qualities = new ArrayList<>();
            // Example regex to extract video qualities (modify as per actual YouTube DOM structure)
            String regex = "\"(qualityLabel|width)\":\"([^\"]+)\"";
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
            java.util.regex.Matcher matcher = pattern.matcher(html);
            while (matcher.find()) {
                if (Objects.equals(matcher.group(1), "qualityLabel")) {
                    qualities.add(matcher.group(2));
                }
            }
            return qualities;
        }

        // Function to strip everything after the first "p" in each quality label
        private static List<String> stripQualityLabels(List<String> qualities) {
            List<String> strippedQualities = new ArrayList<>();
            for (String quality : qualities) {
                int index = quality.indexOf('p');
                strippedQualities.add(index != -1 ? quality.substring(0, index + 1) : quality);
            }
            return strippedQualities;
        }

        // Function to filter out premium formats, remove duplicates, and order from greatest to least
        private static List<String> filterAndRemoveDuplicates(List<String> qualities) {
            List<String> filteredQualities = new ArrayList<>();
            Set<String> seenQualities = new HashSet<>();
            for (String quality : qualities) {
                if (!quality.contains("Premium") && seenQualities.add(quality)) {
                    filteredQualities.add(quality);
                }
            }
            Collections.sort(filteredQualities, getQualitiesAsyncTask::compareQuality);
            return filteredQualities;
        }

        private static int compareQuality(String a, String b) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)p");
            java.util.regex.Matcher matcherA = pattern.matcher(a);
            java.util.regex.Matcher matcherB = pattern.matcher(b);
            int resA = matcherA.find() ? Integer.parseInt(matcherA.group(1)) : 0;
            int resB = matcherB.find() ? Integer.parseInt(matcherB.group(1)) : 0;
            return Integer.compare(resB, resA);
        }

        @Override
        protected Void doInBackground(Uri... uris) {
            MainActivity activity = activityReference.get();
            fetchVideoQualities(qualities -> activity.showQualitySelectionDialog(qualities, activity, uris[0], videoTitle), uris[0]);
            return null;
        }
        private static WeakReference<MainActivity> activityReference;

        public getQualitiesAsyncTask(MainActivity m) {
            activityReference = new WeakReference<>(m);
        }
        // Callback interface for fetched qualities
        interface QualitiesCallback {
            void onQualitiesFetched(List<String> qualities);
        }
    }

    private static class sendRequestToCobaltAsyncTask extends AsyncTask<Uri, Void, Void> {

        private static WeakReference<MainActivity> activityReference;
        private static boolean audioOnly;
        private static String quality;
        private static String format;
        private static String videoTitle;

        public sendRequestToCobaltAsyncTask(MainActivity m, boolean ao, String q, String f, String vt) {
            activityReference = new WeakReference<>(m);
            audioOnly = ao;
            quality = q;
            format = f;
            videoTitle = vt;
        }
        public static String sendRequestToCobalt(String videoUrl) throws IOException, JSONException {
            // https://greasyfork.org/en/scripts/497849-youtube-cobalt-tools-download-button/code
            String codec = "avc1";
            if (format.equals("mp4") && Integer.parseInt(quality.replace("p", "")) > 1080) {
                codec = "av1";
            } else if (format.equals("webm")) {
                codec = "vp9";
            }

            URL url = new URL("https://api.cobalt.tools/api/json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            JSONObject requestBody = new JSONObject();
            requestBody.put("url", videoUrl);
            requestBody.put("vQuality", Integer.parseInt(audioOnly ? quality.replaceAll("\\D", "") : quality.replace("p", "")));
            requestBody.put("codec", codec);
            requestBody.put("filenamePattern", "basic");
            requestBody.put("isAudioOnly", audioOnly);
            requestBody.put("disableMetadata", true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    if (jsonResponse.has("url")) {
                        return jsonResponse.getString("url");
                    } else {
                        throw new IOException("Failed to get URL from Cobalt API");
                    }
                }
            } else {
                throw new IOException("HTTP error code: " + responseCode);
            }
        }
        @Override
        protected Void doInBackground(Uri... uris) {
            MainActivity activity = activityReference.get();
            try {
                if(audioOnly) {
                    activity.downloadFile(slugify(videoTitle.replace(" - YouTube", "")) + "." + format,
                            sendRequestToCobalt(uris[0].toString()), "audio/" + format);
                } else {
                    activity.downloadFile(slugify(videoTitle.replace(" - YouTube", "_" + quality)) + "." + format,
                            sendRequestToCobalt(uris[0].toString()), "video/" + format);
                }
            } catch (IOException | JSONException e) {
                activity.showError(e, activity);
            }
            return null;
        }
        private static String slugify(String word) {
            return Normalizer.normalize(word, Normalizer.Form.NFD)
                    .replaceAll("[^\\p{ASCII}]", "")
                    .replaceAll("[^a-zA-Z0-9\\s]+", "").trim();
        }
    }
    private String icon = "";
    private String title = "";
    private String subtitle = "";
    private long duration;
    private boolean isPlaying = false;

    public void showQualitySelectionDialog(List<String> qualities, Context c, Uri uri, String videoTitle) {
        CharSequence[] apkNames = new CharSequence[qualities.size() + 1];
        boolean[] checkedItems = new boolean[qualities.size() + 1];

        apkNames[0] = getString(R.string.audio);
        for (int i = 0; i < qualities.size(); i++) {
            apkNames[i + 1] = qualities.get(i);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(c, R.style.CustomDialogTheme);
        builder.setTitle("Select Quality");
        builder.setMultiChoiceItems(apkNames, checkedItems, (dialog, which, isChecked) -> {

        });
        builder.setPositiveButton("Download", (dialog, which) -> {
            for (int i = 0; i < checkedItems.length; i++) {
                if (checkedItems[i]) {
                    final boolean isAudio = i == 0;
                    new sendRequestToCobaltAsyncTask(MainActivity.this, isAudio, isAudio ? "1080p" : qualities.get(i-1), isAudio ? "opus" : "mp4", videoTitle).execute(uri);
                }
            }
        });
        builder.setNegativeButton("Cancel", null);

        runOnUiThread(() -> builder.create().show());
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        load(false);
    }

    public void load(boolean dl) {
        web = findViewById(R.id.web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);

        Intent intent = getIntent();
        String action = intent.getAction();
        Uri data = intent.getData();
        String url = "https://m.youtube.com/";
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            url = data.toString();
        } else if (Intent.ACTION_SEND.equals(action)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && (sharedText.startsWith(url) || sharedText.contains("youtube.com") || sharedText.contains("youtu.be"))) {
                // Not possible to specify shared URL as youtube in manifest so check it here
                url = sharedText;
            } else {
                Toast.makeText(this, R.string.not_yt, Toast.LENGTH_LONG).show();
            }
        }
        if(dl) {
            new getQualitiesAsyncTask(MainActivity.this).execute(Uri.parse(url));
        }
        web.loadUrl(url);
        web.getSettings().setDomStorageEnabled(true); web.getSettings().setDatabaseEnabled(true);
        web.addJavascriptInterface(new WebAppInterface(this), "Android");
        web.setWebChromeClient(new CustomWebClient());
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); // Allow autoplay
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);


        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView p1, String p2, Bitmap p3) {
                web.loadUrl("javascript:(function () { document.head.appendChild(document.createElement('style')).innerHTML = 'div:not(button div):not(#container div):not(#dismissible div) { background-color: black !important; }'; })();");
                try {
                    if(p2.startsWith("https://m.youtube.com/watch")) {
                        findViewById(R.id.overlay_test).setVisibility(View.VISIBLE);
                        findViewById(R.id.dl).setOnClickListener(v -> new getQualitiesAsyncTask(MainActivity.this).execute(Uri.parse(p2)));
                    } else {
                        findViewById(R.id.overlay_test).setVisibility(View.GONE);
                    }
                } catch (NullPointerException ignored) {}
                super.onPageStarted(p1, p2, p3);
            }

            @Override
            public void onPageFinished(WebView p1, String url) {

                web.loadUrl("javascript:(function () { var script = document.createElement('script'); script.src='https://cdn.jsdelivr.net/npm/ytpro'; document.body.appendChild(script);  })();");
                web.loadUrl("javascript:(function () { var script = document.createElement('script'); script.src='https://cdn.jsdelivr.net/npm/ytpro/bgplay.js'; document.body.appendChild(script);  })();");

                if(!url.contains("#bgplay") && isPlaying){
                    isPlaying=false;
                    stopService(new Intent(getApplicationContext(), ForegroundService.class));
                }

                super.onPageFinished(p1, url);
            }
        });

        setReceiver();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                web.loadUrl("https://m.youtube.com");
            } else {
                Toast.makeText(getApplicationContext(),getString(R.string.grant_mic), Toast.LENGTH_SHORT).show();
            }
        } else if(requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(getApplicationContext(), getString(R.string.grant_storage), Toast.LENGTH_SHORT).show();
            }
        }
    }
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        }
        else {
            finish();
        }
    }

    @Override
    public void onPictureInPictureModeChanged (boolean isInPictureInPictureMode, Configuration newConfig) {
        web.loadUrl(isInPictureInPictureMode ?
                "javascript:document.getElementsByClassName('video-stream')[0].play();" :
                "javascript:removePIP();");
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        web.loadUrl("javascript:PIPlayer();");
    }



    public class CustomWebClient extends WebChromeClient {
        private View mCustomView;
        private WebChromeClient.CustomViewCallback mCustomViewCallback;
        protected FrameLayout frame;
        private int mOriginalOrientation;
        private int mOriginalSystemUiVisibility;
        public CustomWebClient() {}


        public Bitmap getDefaultVideoPoster() {
            return BitmapFactory.decodeResource(MainActivity.this.getApplicationContext().getResources(), 2130837573);
        }


        public void onShowCustomView(View paramView, WebChromeClient.CustomViewCallback viewCallback) {

            this.mOriginalOrientation = portrait ?
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT :
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;

            if (this.mCustomView != null) {
                onHideCustomView();
                return;
            }
            this.mCustomView = paramView;
            this.mOriginalSystemUiVisibility = MainActivity.this.getWindow().getDecorView().getSystemUiVisibility();
            MainActivity.this.setRequestedOrientation(this.mOriginalOrientation);
            this.mOriginalOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;this.mCustomViewCallback = viewCallback; ((FrameLayout)MainActivity.this.getWindow().getDecorView()).addView(this.mCustomView, new FrameLayout.LayoutParams(-1, -1)); MainActivity.this.getWindow().getDecorView().setSystemUiVisibility(3846);
        }
        public void onHideCustomView() {

            ((FrameLayout)MainActivity.this.getWindow().getDecorView()).removeView(this.mCustomView);
            this.mCustomView = null;
            MainActivity.this.getWindow().getDecorView().setSystemUiVisibility(this.mOriginalSystemUiVisibility);
            MainActivity.this.setRequestedOrientation(this.mOriginalOrientation);
            this.mOriginalOrientation = portrait ?
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT :
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;

            this.mCustomViewCallback = null;
            web.clearFocus();
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            if(Build.VERSION.SDK_INT > 22 && request.getOrigin().toString().contains("youtube.com")) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED) {
                    requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 101);
                } else {
                    request.grant(request.getResources());
                }
            }
        }
    }

    private void downloadFile(String filename, String url, String mtype) {
        if (Build.VERSION.SDK_INT > 22 && Build.VERSION.SDK_INT < 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            runOnUiThread(() -> Toast.makeText(getApplicationContext(), R.string.grant_storage, Toast.LENGTH_SHORT).show());
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        try {
            try {
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(new DownloadManager.Request(Uri.parse(url)).setTitle(filename)
                        .setDescription(filename)
                        .setMimeType(mtype)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20"))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE |
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED));
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), R.string.dl_started, Toast.LENGTH_SHORT).show());
            } catch (UnsupportedEncodingException e) {
                showError(e, this);
            }
        } catch (Exception e) {
            showError(e, this);
        }
    }

    private void showError(Exception e, Context c) {
        final String mainErr = e.toString();
        StringBuilder stackTrace = new StringBuilder().append(mainErr);
        for(StackTraceElement line : e.getStackTrace()) {
            stackTrace.append(line);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(c, R.style.CustomDialogTheme);
        builder.setTitle(R.string.error);
        builder.setMessage(stackTrace);
        runOnUiThread(() -> {
            builder.create().show();
            Toast.makeText(this, mainErr, Toast.LENGTH_SHORT).show();
        });
    }
    public class WebAppInterface {
        Context mContext;
        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void showToast(String txt) {
            Toast.makeText(getApplicationContext(), txt, Toast.LENGTH_SHORT).show();
        }
        @JavascriptInterface
        public void gohome(String x) {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(startMain);
        }

        @JavascriptInterface
        public void downvid(String name,String url, String m) {
            downloadFile(name,url,m);
        }
        @JavascriptInterface
        public void fullScreen(boolean value){
            portrait =  value;
        }
        @JavascriptInterface
        public void oplink(String url) {
            Intent i = new Intent();
            i.setAction(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));

            startActivity(i);
        }
        @JavascriptInterface
        public String getInfo() {
            PackageManager manager = getApplicationContext().getPackageManager();
            try{
                PackageInfo info = manager.getPackageInfo(getApplicationContext().getPackageName(), PackageManager.GET_ACTIVITIES);
                return info.versionName;
            } catch(PackageManager.NameNotFoundException e){
                return "1.0";
            }

        }

        @JavascriptInterface
        public void bgStart(String iconn , String titlen , String subtitlen,long dura) {
//  ForegroundService.setupNotification(
            icon  =iconn;
            title =titlen;
            subtitle=subtitlen;
            duration= dura;
            isPlaying=true;

            Intent intent = new Intent(getApplicationContext(), ForegroundService.class);

// Add extras to the Intent
            intent.putExtra("icon", icon);
            intent.putExtra("title", title);
            intent.putExtra("subtitle", subtitle);
            intent.putExtra("duration", duration);
            intent.putExtra("currentPosition", 0);

            startService(intent);
        }

        @JavascriptInterface
        public void bgUpdate(String iconn , String titlen , String subtitlen,long dura) {


            icon =iconn;
            title =titlen;
            subtitle=subtitlen;
            duration=(long)(dura);


            getApplicationContext().sendBroadcast(new Intent("UPDATE_NOTIFICATION")
                    .putExtra("icon", icon)
                    .putExtra("title", title)
                    .putExtra("subtitle", subtitle)
                    .putExtra("duration", duration)
                    .putExtra("currentPosition", "0")
                    .putExtra("action", "pause")
            );
        }
        @JavascriptInterface
        public void bgStop() {
            Log.e("hii","stop");

            isPlaying=false;

            stopService(new Intent(getApplicationContext(), ForegroundService.class));



        }
        @JavascriptInterface
        public void bgPause(long ct) {
            Log.e("hii","pause");


//ForegroundService.updateNotification(icon,title,subtitle,"play", getApplicationContext(),duration,ct);

            getApplicationContext().sendBroadcast(new Intent("UPDATE_NOTIFICATION")
                    .putExtra("icon", icon)
                    .putExtra("title", title)
                    .putExtra("subtitle", subtitle)
                    .putExtra("duration", duration)
                    .putExtra("currentPosition", ct)
                    .putExtra("action", "pause")
            );

        }
        @JavascriptInterface
        public void bgPlay(long ct) {
            Log.e("hii","play");
//ForegroundService.updateNotification(icon,title,subtitle,"pause",getApplicationContext(),duration,ct);

            getApplicationContext().sendBroadcast(new Intent("UPDATE_NOTIFICATION")
                    .putExtra("icon", icon)
                    .putExtra("title", title)
                    .putExtra("subtitle", subtitle)
                    .putExtra("duration", duration)
                    .putExtra("currentPosition", ct)
                    .putExtra("action", "play")
            );

        }
        @JavascriptInterface
        public void bgBuffer(long ct) {
            Log.e("hii","play");
//ForegroundService.updateNotification(icon,title,subtitle,"buffer",getApplicationContext(),duration,ct);


            getApplicationContext().sendBroadcast(new Intent("UPDATE_NOTIFICATION")
                    .putExtra("icon", icon)
                    .putExtra("title", title)
                    .putExtra("subtitle", subtitle)
                    .putExtra("duration", duration)
                    .putExtra("currentPosition", ct)
                    .putExtra("action", "buffer")
            );


        }
        @JavascriptInterface
        public void pipvid(String x) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9)).build());
                } catch (IllegalStateException e) {
                    showError(e, MainActivity.this);
                }
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.no_pip), Toast.LENGTH_SHORT).show();
            }
        }}





    public void setReceiver(){
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getExtras().getString("actionname");

                switch (action) {
                    case "PLAY_ACTION":
                    case "PAUSE_ACTION":
                        web.loadUrl("javascript:playPause();");
                        break;
                    case "NEXT_ACTION":
                        web.loadUrl("javascript:playNext();");
                        break;
                    case "PREV_ACTION":
                        web.loadUrl("javascript:playPrev();");
                        break;
                    case "SEEKTO":
                        web.loadUrl("javascript:seekTo('" + intent.getExtras().getString("pos") + "');");
                        break;
                }
                Log.e("Action",action);
            }
        };

        if (Build.VERSION.SDK_INT > 32) {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver(broadcastReceiver);
    }

}
