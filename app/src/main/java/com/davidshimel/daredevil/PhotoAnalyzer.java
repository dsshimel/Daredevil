package com.davidshimel.daredevil;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.AccountPicker;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;

import com.google.api.services.vision.v1.Vision;
import com.google.api.services.vision.v1.VisionRequestInitializer;
import com.google.api.services.vision.v1.VisionScopes;
import com.google.api.services.vision.v1.model.AnnotateImageRequest;
import com.google.api.services.vision.v1.model.AnnotateImageResponse;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesResponse;
import com.google.api.services.vision.v1.model.Feature;
import com.google.api.services.vision.v1.model.Image;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class PhotoAnalyzer extends AppCompatActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback {

    public static final String TAG = "PhotoAnalyzer";

    private static final String OAUTH_CLIENT_ID =
            "";
    private static final String VISION_OAUTH_SCOPE =
            "https://www.googleapis.com/auth/cloud-platform";

    private static final int GET_PHOTO_REQUEST_CODE = 1;
    private static final int WRITE_STORAGE_REQUEST_CODE = 2;
    private static final int PICK_ACCOUNT_REQUEST_CODE = 3;
    private static final int RECOVERABLE_AUTH_REQUEST_CODE = 4;

    private ImageView photoDisplay;
    private Button getPhotoButton;
    private Button analyzeButton;
    private File photoFile = null;
    private Bitmap photoBitmap = null;
    private String accountEmail = null;
    private String accountType = null;
    private String oauthToken = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_analyzer);

        boolean canWriteExternalStorage =
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "can write external storage: " + canWriteExternalStorage);

        getPhotoButton = (Button) findViewById(R.id.get_photo_button);
        if (!canWriteExternalStorage) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE },
                    WRITE_STORAGE_REQUEST_CODE);
        } else {
            getPhotoButton.setVisibility(View.VISIBLE);
        }

        getPhotoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                Toast sanityCheck = Toast.makeText(
//                        getApplicationContext(), "You clicked it", Toast.LENGTH_LONG);
//                sanityCheck.show();
                boolean photoIntentSuccess = tryStartPhotoIntentActivity();
                Log.d(TAG, "tryStartPhotoIntentActivity " + photoIntentSuccess);
            }
        });

        photoDisplay = (ImageView) findViewById(R.id.photo_thumbnail);

        analyzeButton = (Button) findViewById(R.id.analyze_photo_button);
        analyzeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (photoBitmap != null) {
                    setupOauth();
//                    makeVisionRequest();
                }
            }
        });
    }

    private boolean tryStartPhotoIntentActivity() {
        boolean result = false;
        Intent getPhotoIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Performing this check is important because if you call startActivityForResult()
        // using an intent that no app can handle, your app will crash.
        if (getPhotoIntent.resolveActivity(getPackageManager()) != null) {
            photoFile = null;
            File directory = null;
            photoBitmap = null;
            analyzeButton.setVisibility(View.GONE);
            photoDisplay.setVisibility(View.GONE);
            try {
                directory = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES);
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String photoFileName = getString(R.string.app_name) + "_" + timeStamp + "_";
                photoFile = File.createTempFile(photoFileName, ".jpg", directory);
            } catch (IOException ioe) {
                Log.e(TAG, "failed to save photo at " + photoFile.getAbsolutePath()
                        + " in directory " + directory.getAbsolutePath());
                Log.e(TAG, ioe.toString());
            }

            if (photoFile != null) {
                Log.i(TAG, "saving photo at " + photoFile.getAbsolutePath());
                // http://developer.android.com/reference/android/provider/MediaStore.html#EXTRA_OUTPUT
                getPhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(photoFile));
                startActivityForResult(getPhotoIntent, GET_PHOTO_REQUEST_CODE);
                result = true;
            }
        }

        return result;
    }

    private void setupOauth() {
        if (accountEmail == null && accountType == null) {
            String[] accountTypes = new String[]{"com.google"};
            Intent intent = AccountPicker.newChooseAccountIntent(null, null,
                    accountTypes, false, null, null, null, null);
            startActivityForResult(intent, PICK_ACCOUNT_REQUEST_CODE);
        } else {
            if (oauthToken == null) {
                VisionOauthTask authTask = new VisionOauthTask(
                        this, accountEmail, accountType, "oauth2:" + VISION_OAUTH_SCOPE);
                authTask.execute();
            } else {
                Log.i(TAG, "oauth token is ready to go");
            }
        }
    }

    private class VisionOauthTask extends AsyncTask<Void, Void, Void> {
        private final Activity activity;
        private final String accountEmail;
        private final String accountType;
        private final String scope;

        public VisionOauthTask(
                Activity activity, String accountEmail, String accountType, String scope) {
            this.activity = activity;
            this.accountEmail = accountEmail;
            this.accountType = accountType;
            this.scope = scope;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                Account account = new Account(accountEmail, accountType);
                String token = GoogleAuthUtil.getToken(activity, account, scope);
                if (token != null) {
                    Log.i(TAG, "oauth token = " + token);
                } else {
                    Log.i(TAG, "oauth token is null");
                }
            } catch (UserRecoverableAuthException recoverableEx) {
                Log.e(TAG, recoverableEx.toString());
                startActivityForResult(recoverableEx.getIntent(), RECOVERABLE_AUTH_REQUEST_CODE);
            }
            catch (GoogleAuthException | IOException ex) {
                Log.e(TAG, ex.toString());
            }
            return null;
        }
    }

    private void makeVisionRequest() {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        boolean streamSuccess = photoBitmap.compress(
                Bitmap.CompressFormat.JPEG, 100 /* quality */, byteStream);
//        String photoBase64 = Base64.encodeToString(photoBytes, Base64.DEFAULT);
        Image photoBase64 = new Image();
        photoBase64.encodeContent(byteStream.toByteArray());

        try {
            NetHttpTransport.Builder transportBuilder = new NetHttpTransport.Builder();
            HttpTransport httpTransport = transportBuilder.build();
            HttpRequestFactory factory = httpTransport.createRequestFactory();

            // Throws network operation on main thread
//            GoogleCredential credential = GoogleCredential.getApplicationDefault();
//            List<String> scopes = new ArrayList<>();
//            scopes.add("https://www.googleapis.com/auth/cloud-platform");
//            credential = credential.createScoped(scopes);


            Vision.Builder visionBuilder = new Vision.Builder(httpTransport, new JacksonFactory(), null);
//            VisionRequestInitializer requestInitializer = new VisionRequestInitializer(API_KEY);
            VisionRequestInitializer requestInitializer = new VisionRequestInitializer("asdfhjk");
            visionBuilder.setVisionRequestInitializer(requestInitializer);

            Vision vision = visionBuilder.build();
            BatchAnnotateImagesRequest batchRequest = new BatchAnnotateImagesRequest();

            AnnotateImageRequest annotateImageRequest = new AnnotateImageRequest();
            annotateImageRequest.setImage(photoBase64);

            Feature labelDetectionFeature = new Feature();
            labelDetectionFeature.setType("LABEL_DETECTION");
            labelDetectionFeature.setMaxResults(10);
            List<Feature> features = new ArrayList<>();
            features.add(labelDetectionFeature);

            annotateImageRequest.setFeatures(features);

            List<AnnotateImageRequest> requests = new ArrayList<>();
            requests.add(annotateImageRequest);

            Vision.Images.Annotate annotateRequest = vision.images().annotate(batchRequest);
            annotateRequest.setDisableGZipContent(true);

            ApiRequestTask task = new ApiRequestTask();
            task.execute(annotateRequest);

//            GenericUrl url = new GenericUrl(API_URL);
//            JSONObject imageContent = new JSONObject();
//            imageContent.put("content", photoBase64);
//
//            JSONObject feature = new JSONObject();
//            feature.put("type", "LABEL_DETECTION");
//            feature.put("maxResults", 1);
//            JSONArray features = new JSONArray();
//            features.put(feature);
//
//            JSONObject request = new JSONObject();
//            request.put("image", imageContent);
//            request.put("features", features);
//            JSONArray requests = new JSONArray();
//            requests.put(request);
//            JSONObject data = new JSONObject();
//            data.put("requests", requests);
//            HttpContent content = new JsonHttpContent(new JacksonFactory(), data);
//            HttpRequest postRequest = factory.buildPostRequest(url, content);
//            ApiRequestTask task = new ApiRequestTask();
//            task.execute(postRequest);
//            HttpResponse postResponse = postRequest.execute();
        } catch (IOException ioe) {
            Log.e(TAG, ioe.toString());
//        } catch (JSONException je) {
//            Log.e(TAG, je.toString());
        } catch (RuntimeException re) {
            Log.e(TAG, re.toString());
        }
    }

    private class ApiRequestTask extends AsyncTask<Vision.Images.Annotate, Void, BatchAnnotateImagesResponse> {

        @Override
        protected BatchAnnotateImagesResponse doInBackground(Vision.Images.Annotate... annotateRequests) {
            BatchAnnotateImagesResponse result = null;
            try {
                result = annotateRequests[0].execute();
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }

            return result;
        }

        @Override
        protected void onPostExecute(BatchAnnotateImagesResponse response) {
            if (response != null) {
                AnnotateImageResponse annotateResponse = response.getResponses().get(0);
                Log.i(TAG, annotateResponse.toString());
            }
        }
    }

//    private class VisionApiAuthTask extends AsyncTask<Void, Void, Vision> {
//        @Override
//        protected Vision doInBackground(Void... params) {
//            try {
//                GoogleCredential credential =
//                        GoogleCredential.getApplicationDefault().createScoped(VisionScopes.all());
//
//            } catch (IOException ioe) {
//                Log.e(TAG, ioe.toString());
//                return null;
//            }
//        }
//    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case GET_PHOTO_REQUEST_CODE:
                    extractPhotoFromIntentData(data);
                    break;
                case PICK_ACCOUNT_REQUEST_CODE:
                    // Watch out for infinite OAuth loop here.
                    if (extractAccountInfoFromIntentData(data)) {
                        setupOauth();
                    } else {
                        Log.i(TAG, "could not get account info; account email = " + accountEmail
                                + ", account type = " + accountType);
                    }
                    break;
                case RECOVERABLE_AUTH_REQUEST_CODE:
                    Log.i(TAG, "successfully recovered from user auth exception");
                    setupOauth();
                    break;
            }
        }
    }

    private void extractPhotoFromIntentData(Intent data) {
        Bundle extras = data.getExtras();
        if (extras != null) {
            Bitmap photoBitmap = (Bitmap) extras.get("data");
            BitmapDrawable photoDrawable = new BitmapDrawable(getResources(), photoBitmap);
            photoDisplay.setImageDrawable(photoDrawable);
        } else {
            // http://stackoverflow.com/questions/9890757/android-camera-data-intent-returns-null
            Log.i(TAG, "saved photo at " + photoFile.getAbsolutePath());
        }

        if (photoFile != null) {
            analyzeButton.setVisibility(View.VISIBLE);
            photoDisplay.setVisibility(View.VISIBLE);
            BitmapFactory.Options options = new BitmapFactory.Options();
            // http://developer.android.com/training/displaying-bitmaps/load-bitmap.html
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
            int originalWidth = options.outWidth;
            int originalHeight = options.outHeight;
            int requestWidth;
            int requestHeight;
            if (originalWidth > originalHeight) {
                requestWidth = 640;
                requestHeight = 480;
            } else {
                requestWidth = 480;
                requestHeight = 640;
            }

            options.inJustDecodeBounds = false;
            // inSampleSize must be greater than 1
            // http://developer.android.com/reference/android/graphics/BitmapFactory.Options.html#inSampleSize
            options.inSampleSize = Math.min(
                    originalWidth / requestWidth, originalHeight / requestHeight);
            photoBitmap = BitmapFactory.decodeFile(
                    photoFile.getAbsolutePath(), options);
            Drawable photoDrawable = new BitmapDrawable(getResources(), photoBitmap);
            photoDisplay.setImageDrawable(photoDrawable);
        }
    }

    private boolean extractAccountInfoFromIntentData(Intent data) {
        accountEmail = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        accountType = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
        return accountEmail != null && accountType != null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == WRITE_STORAGE_REQUEST_CODE) {
            Log.i(TAG, "permissions results: " + permissions);
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getPhotoButton.setVisibility(View.VISIBLE);
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
