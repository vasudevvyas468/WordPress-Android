package org.wordpress.android.util;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.support.v4.content.IntentCompat;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.wordpress.android.Constants;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.models.Blog;
import org.wordpress.android.models.FeatureSet;
import org.wordpress.android.models.MediaFile;
import org.wordpress.android.models.Post;
import org.wordpress.android.ui.media.MediaUtils;
import org.wordpress.android.ui.posts.PagesActivity;
import org.wordpress.android.ui.posts.PostsActivity;
import org.wordpress.android.util.AppLog.T;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlrpc.android.ApiHelper;
import org.xmlrpc.android.XMLRPCClient;
import org.xmlrpc.android.XMLRPCClientInterface;
import org.xmlrpc.android.XMLRPCException;
import org.xmlrpc.android.XMLRPCFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostUploadService extends Service {
    private static Context context;
    private static final ArrayList<Post> listOfPosts = new ArrayList<Post>();
    private static NotificationManager nm;
    private static Post currentUploadingPost = null;
    private UploadPostTask currentTask = null;
    private FeatureSet mFeatureSet;

    public static void addPostToUpload(Post currentPost) {
        synchronized (listOfPosts) {
            listOfPosts.add(currentPost);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = this.getApplicationContext();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        synchronized (listOfPosts) {
            if (listOfPosts.size() == 0 || context == null) {
                this.stopSelf();
                return;
            }
        }
        uploadNextPost();
    }

    private FeatureSet synchronousGetFeatureSet() {
        if (WordPress.getCurrentBlog() == null || !WordPress.getCurrentBlog().isDotcomFlag())
            return null;
        ApiHelper.GetFeatures task = new ApiHelper.GetFeatures();
        List<Object> apiArgs = new ArrayList<Object>();
        apiArgs.add(WordPress.getCurrentBlog());
        mFeatureSet = task.doSynchronously(apiArgs);
        return mFeatureSet;
    }

    private void uploadNextPost(){
        synchronized (listOfPosts) {
            if( currentTask == null ) { //make sure nothing is running
                currentUploadingPost = null;
                if ( listOfPosts.size() > 0 ) {
                    currentUploadingPost = listOfPosts.remove(0);
                    currentTask = new UploadPostTask();
                    currentTask.execute(currentUploadingPost);
                } else {
                    this.stopSelf();
                }
            }
        }
    }

    private void postUploaded() {
        synchronized (listOfPosts) {
            currentTask = null;
            currentUploadingPost = null;
        }
        uploadNextPost();
    }

    public static boolean isUploading(Post post) {
        if ( currentUploadingPost != null && currentUploadingPost.equals(post) )
            return true;
        if( listOfPosts != null && listOfPosts.size() > 0 && listOfPosts.contains(post))
            return true;
        return false;
    }

    private class UploadPostTask extends AsyncTask<Post, Boolean, Boolean> {
        private Post post;
        private String mErrorMessage = "";
        private boolean mIsMediaError = false;
        private boolean mErrorUnavailableVideoPress = false;
        private int featuredImageID = -1;
        private int notificationID;
        private Notification n;

        @Override
        protected void onPostExecute(Boolean postUploadedSuccessfully) {
            if (postUploadedSuccessfully) {
                WordPress.postUploaded(post.getPostid());
                nm.cancel(notificationID);
                WordPress.wpDB.deleteMediaFilesForPost(post);
            } else {
                String postOrPage = (String) (post.isPage() ? context.getResources().getText(R.string.page_id) : context.getResources()
                        .getText(R.string.post_id));
                Intent notificationIntent = new Intent(context, (post.isPage()) ? PagesActivity.class : PostsActivity.class);
                notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK
                        | IntentCompat.FLAG_ACTIVITY_CLEAR_TASK);
                notificationIntent.setAction("android.intent.action.MAIN");
                notificationIntent.addCategory("android.intent.category.LAUNCHER");
                notificationIntent.setData((Uri.parse("custom://wordpressNotificationIntent" + post.getBlogID())));
                notificationIntent.putExtra("errorMessage", mErrorMessage);
                if (mErrorUnavailableVideoPress) {
                    notificationIntent.putExtra("errorInfoTitle", getString(R.string.learn_more));
                    notificationIntent.putExtra("errorInfoLink", Constants.videoPressURL);
                }
                notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
                        notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                n.flags |= Notification.FLAG_AUTO_CANCEL;
                n.icon = android.R.drawable.stat_notify_error;
                String errorText = context.getResources().getText(R.string.upload_failed).toString();
                if (mIsMediaError)
                    errorText = context.getResources().getText(R.string.media) + " " + context.getResources().getText(R.string.error);
                n.setLatestEventInfo(context, (mIsMediaError) ? errorText : context.getResources().getText(R.string.upload_failed),
                        (mIsMediaError) ? mErrorMessage : postOrPage + " " + errorText + ": " + mErrorMessage, pendingIntent);

                nm.notify(notificationID, n); // needs a unique id
            }

            postUploaded();
        }

        @Override
        protected Boolean doInBackground(Post... posts) {
            mErrorUnavailableVideoPress = false;
            post = posts[0];

            // add the uploader to the notification bar
            nm = (NotificationManager) SystemServiceFactory.get(context, Context.NOTIFICATION_SERVICE);

            String postOrPage = (String) (post.isPage() ? context.getResources().getText(R.string.page_id) : context.getResources()
                    .getText(R.string.post_id));
            String message = context.getResources().getText(R.string.uploading) + " " + postOrPage;
            n = new Notification(R.drawable.notification_icon, message, System.currentTimeMillis());

            Intent notificationIntent = new Intent(context, PostsActivity.class);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK
                    | IntentCompat.FLAG_ACTIVITY_CLEAR_TASK);
            notificationIntent.setAction("android.intent.action.MAIN");
            notificationIntent.addCategory("android.intent.category.LAUNCHER");
            notificationIntent.setData((Uri.parse("custom://wordpressNotificationIntent" + post.getBlogID())));
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, Intent.FLAG_ACTIVITY_CLEAR_TOP);

            n.setLatestEventInfo(context, message, message, pendingIntent);

            notificationID = (new Random()).nextInt() + post.getBlogID();
            nm.notify(notificationID, n); // needs a unique id

            Blog blog;
            try {
                blog = WordPress.wpDB.instantiateBlogByLocalId(post.getBlogID());
            } catch (Exception e) {
                mErrorMessage = context.getString(R.string.blog_not_found);
                return false;
            }

            if (post.getPost_status() == null) {
                post.setPost_status("publish");
            }
            Boolean publishThis = false;

            String descriptionContent = "", moreContent = "";
            int moreCount = 1;
            if (post.getMt_text_more() != null)
                moreCount++;
            String imgTags = "<img[^>]+android-uri\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>";
            Pattern pattern = Pattern.compile(imgTags);

            for (int x = 0; x < moreCount; x++) {

                if (x == 0)
                    descriptionContent = post.getDescription();
                else
                    moreContent = post.getMt_text_more();

                Matcher matcher;

                if (x == 0) {
                    matcher = pattern.matcher(descriptionContent);
                } else {
                    matcher = pattern.matcher(moreContent);
                }

                List<String> imageTags = new ArrayList<String>();
                while (matcher.find()) {
                    imageTags.add(matcher.group());
                }

                for (String tag : imageTags) {

                    Pattern p = Pattern.compile("android-uri=\"([^\"]+)\"");
                    Matcher m = p.matcher(tag);
                    if (m.find()) {
                        String imgPath = m.group(1);
                        if (!imgPath.equals("")) {
                            MediaFile mf = WordPress.wpDB.getMediaFile(imgPath, post);

                            if (mf != null) {
                                String imgHTML = uploadMediaFile(mf, blog);
                                if (imgHTML != null) {
                                    if (x == 0) {
                                        descriptionContent = descriptionContent.replace(tag, imgHTML);
                                    } else {
                                        moreContent = moreContent.replace(tag, imgHTML);
                                    }
                                } else {
                                    if (x == 0)
                                        descriptionContent = descriptionContent.replace(tag, "");
                                    else
                                        moreContent = moreContent.replace(tag, "");
                                    mIsMediaError = true;
                                }
                            }
                        }
                    }
                }
            }

            // If media file upload failed, let's stop here and prompt the user
            if (mIsMediaError)
                return false;

            JSONArray categoriesJsonArray = post.getJSONCategories();
            String[] theCategories = null;
            if (categoriesJsonArray != null) {
                theCategories = new String[categoriesJsonArray.length()];
                for (int i = 0; i < categoriesJsonArray.length(); i++) {
                    try {
                        theCategories[i] = TextUtils.htmlEncode(categoriesJsonArray.getString(i));
                    } catch (JSONException e) {
                        AppLog.e(T.POSTS, e);
                    }
                }
            }

            Map<String, Object> contentStruct = new HashMap<String, Object>();

            if (!post.isPage() && post.isLocalDraft()) {
                // add the tagline
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

                if (prefs.getBoolean("wp_pref_signature_enabled", false)) {
                    String tagline = prefs.getString("wp_pref_post_signature", "");
                    if (tagline != null) {
                        String tag = "\n\n<span class=\"post_sig\">" + tagline + "</span>\n\n";
                        if (TextUtils.isEmpty(moreContent))
                            descriptionContent += tag;
                        else
                            moreContent += tag;
                    }
                }
            }

            // post format
            if (!post.isPage()) {
                if (!TextUtils.isEmpty(post.getWP_post_format())) {
                    contentStruct.put("wp_post_format", post.getWP_post_format());
                }
            }

            contentStruct.put("post_type", (post.isPage()) ? "page" : "post");
            contentStruct.put("title", post.getTitle());
            long pubDate = post.getDate_created_gmt();
            if (pubDate != 0) {
                Date date_created_gmt = new Date(pubDate);
                contentStruct.put("date_created_gmt", date_created_gmt);
                Date dateCreated = new Date(pubDate + (date_created_gmt.getTimezoneOffset() * 60000));
                contentStruct.put("dateCreated", dateCreated);
            }

            if (!moreContent.equals("")) {
                descriptionContent = descriptionContent.trim() + "<!--more-->" + moreContent;
                post.setMt_text_more("");
            }

            // get rid of the p and br tags that the editor adds.
            if (post.isLocalDraft()) {
                descriptionContent = descriptionContent.replace("<p>", "").replace("</p>", "\n").replace("<br>", "");
            }

            // gets rid of the weird character android inserts after images
            descriptionContent = descriptionContent.replaceAll("\uFFFC", "");

            contentStruct.put("description", descriptionContent);
            if (!post.isPage()) {
                if (!TextUtils.isEmpty(post.getMt_keywords())) {
                    contentStruct.put("mt_keywords", post.getMt_keywords());
                }

                if (theCategories != null && theCategories.length > 0)
                    contentStruct.put("categories", theCategories);
            }

            if (post.getMt_excerpt() != null)
                contentStruct.put("mt_excerpt", post.getMt_excerpt());

            contentStruct.put((post.isPage()) ? "page_status" : "post_status", post.getPost_status());
            if (!post.isPage()) {
                if (post.getLatitude() > 0) {
                    Map<Object, Object> hLatitude = new HashMap<Object, Object>();
                    hLatitude.put("key", "geo_latitude");
                    hLatitude.put("value", post.getLatitude());

                    Map<Object, Object> hLongitude = new HashMap<Object, Object>();
                    hLongitude.put("key", "geo_longitude");
                    hLongitude.put("value",post.getLongitude());

                    Map<Object, Object> hPublic = new HashMap<Object, Object>();
                    hPublic.put("key", "geo_public");
                    hPublic.put("value", 1);

                    Object[] geo = {hLatitude, hLongitude, hPublic};

                    contentStruct.put("custom_fields", geo);
                }
            }

            // featured image
            if (featuredImageID != -1) {
                contentStruct.put("wp_post_thumbnail", featuredImageID);
            }
            XMLRPCClientInterface client = XMLRPCFactory.instantiate(blog.getUri(), blog.getHttpuser(),
                    blog.getHttppassword());
            if (post.getQuickPostType() != null) {
                client.addQuickPostHeader(post.getQuickPostType());
            }
            n.setLatestEventInfo(context, message, message, n.contentIntent);
            nm.notify(notificationID, n);
            if (post.getWP_password() != null) {
                contentStruct.put("wp_password", post.getWP_password());
            }
            Object[] params;

            if (post.isLocalDraft() && !post.isUploaded())
                params = new Object[]{blog.getRemoteBlogId(), blog.getUsername(), blog.getPassword(),
                        contentStruct, publishThis};
            else
                params = new Object[]{post.getPostid(), blog.getUsername(), blog.getPassword(), contentStruct,
                        publishThis};

            try {
                if (post.isLocalDraft() && !post.isUploaded()) {
                    Object newPostId = client.call("metaWeblog.newPost", params);
                    if (newPostId instanceof String) {
                        post.setPostid((String) newPostId);
                    }
                } else {
                    client.call("metaWeblog.editPost", params);
                }

                post.setUploaded(true);
                post.setLocalChange(false);
                post.update();
                return true;
            } catch (final XMLRPCException e) {
                setUploadPostErrorMessage(e);
            } catch (IOException e) {
                setUploadPostErrorMessage(e);
            } catch (XmlPullParserException e) {
                setUploadPostErrorMessage(e);
            }

            return false;
        }

        
        private void setUploadPostErrorMessage(Exception e) {
            mErrorMessage = String.format(context.getResources().getText(R.string.error_upload).toString(), post.isPage() ? context
                    .getResources().getText(R.string.page).toString() : context.getResources().getText(R.string.post).toString())
                    + " " + e.getMessage();
            mIsMediaError = false;
            AppLog.e(T.EDITOR, mErrorMessage, e);
        }
        
        public String uploadMediaFile(MediaFile mf, Blog blog) {
            String content = "";

            String curImagePath = mf.getFilePath();
            if (curImagePath == null) {
                return null;
            }

            if (curImagePath.contains("video")) {
                // Upload the video
                XMLRPCClientInterface client = XMLRPCFactory.instantiate(blog.getUri(), blog.getHttpuser(),
                        blog.getHttppassword());
                // create temp file for media upload
                String tempFileName = "wp-" + System.currentTimeMillis();
                try {
                    context.openFileOutput(tempFileName, Context.MODE_PRIVATE);
                } catch (FileNotFoundException e) {
                    mErrorMessage = getResources().getString(R.string.file_error_create);
                    mIsMediaError = true;
                    return null;
                }

                Uri videoUri = Uri.parse(curImagePath);
                File videoFile = null;
                String mimeType = "", xRes = "", yRes = "";

                if (videoUri.toString().contains("content:")) {

                    String[] projection = new String[]{Video.Media._ID, Video.Media.DATA, Video.Media.MIME_TYPE, Video.Media.RESOLUTION};
                    Cursor cur = context.getContentResolver().query(videoUri, projection, null, null, null);

                    if (cur != null && cur.moveToFirst()) {

                        int mimeTypeColumn, resolutionColumn, dataColumn;

                        dataColumn = cur.getColumnIndex(Video.Media.DATA);
                        mimeTypeColumn = cur.getColumnIndex(Video.Media.MIME_TYPE);
                        resolutionColumn = cur.getColumnIndex(Video.Media.RESOLUTION);

                        mf = new MediaFile();

                        String thumbData = cur.getString(dataColumn);
                        mimeType = cur.getString(mimeTypeColumn);

                        videoFile = new File(thumbData);
                        mf.setFilePath(videoFile.getPath());
                        String resolution = cur.getString(resolutionColumn);
                        if (resolution != null) {
                            String[] resx = resolution.split("x");
                            xRes = resx[0];
                            yRes = resx[1];
                        } else {
                            // set the width of the video to the thumbnail width, else 640x480
                            if (!blog.getMaxImageWidth().equals("Original Size")) {
                                xRes = blog.getMaxImageWidth();
                                yRes = String.valueOf(Math.round(Integer.valueOf(blog.getMaxImageWidth()) * 0.75));
                            } else {
                                xRes = "640";
                                yRes = "480";
                            }
                        }
                    }
                } else { // file is not in media library
                    String filePath = videoUri.toString().replace("file://", "");
                    mf.setFilePath(filePath);
                    videoFile = new File(filePath);
                }

                if (videoFile == null) {
                    mErrorMessage = context.getResources().getString(R.string.error_media_upload);
                    return null;
                }

                if (TextUtils.isEmpty(mimeType)) {
                    mimeType = getMediaFileMimeType(videoFile, false);
                }
                String videoName = getMediaFileName(videoFile, mimeType);

                // try to upload the video
                Map<String, Object> m = new HashMap<String, Object>();
                m.put("name", videoName);
                m.put("type", mimeType);
                m.put("bits", mf);
                m.put("overwrite", true);

                Object[] params = {1, blog.getUsername(), blog.getPassword(), m};

                FeatureSet featureSet = synchronousGetFeatureSet();
                boolean selfHosted = WordPress.currentBlog != null && !WordPress.currentBlog.isDotcomFlag();
                boolean isVideoEnabled = selfHosted || (featureSet != null && mFeatureSet.isVideopressEnabled());
                if (isVideoEnabled) {
                    File tempFile;
                    try {
                        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(videoName);
                        tempFile = createTempUploadFile(fileExtension);
                    } catch (IOException e) {
                        mErrorMessage = getResources().getString(R.string.file_error_create);
                        mIsMediaError = true;
                        return null;
                    }

                    Object result = uploadFileHelper(client, params, tempFile);
                    Map<?, ?> resultMap = (HashMap<?, ?>) result;
                    if (resultMap != null && resultMap.containsKey("url")) {
                        String resultURL = resultMap.get("url").toString();
                        if (resultMap.containsKey("videopress_shortcode")) {
                            resultURL = resultMap.get("videopress_shortcode").toString() + "\n";
                        } else {
                            resultURL = String.format(
                                    "<video width=\"%s\" height=\"%s\" controls=\"controls\"><source src=\"%s\" type=\"%s\" /><a href=\"%s\">Click to view video</a>.</video>",
                                    xRes, yRes, resultURL, mimeType, resultURL);
                        }
                        content = content + resultURL;
                    } else {
                        return null;
                    }
                } else {
                    mErrorMessage = getString(R.string.media_no_video_message);
                    mErrorUnavailableVideoPress = true;
                    return null;
                }
            } else {
                // Upload the image
                curImagePath = mf.getFilePath();

                Uri imageUri = Uri.parse(curImagePath);
                File imageFile = null;
                String mimeType = "", orientation = "", path = "";

                if (imageUri.toString().contains("content:")) {
                    String[] projection;
                    Uri imgPath;

                    projection = new String[]{Images.Media._ID, Images.Media.DATA, Images.Media.MIME_TYPE,
                            Images.Media.ORIENTATION};

                    imgPath = imageUri;

                    Cursor cur = context.getContentResolver().query(imgPath, projection, null, null, null);

                    if (cur.moveToFirst()) {

                        int dataColumn, mimeTypeColumn, orientationColumn;

                        dataColumn = cur.getColumnIndex(Images.Media.DATA);
                        mimeTypeColumn = cur.getColumnIndex(Images.Media.MIME_TYPE);
                        orientationColumn = cur.getColumnIndex(Images.Media.ORIENTATION);

                        orientation = cur.getString(orientationColumn);
                        String thumbData = cur.getString(dataColumn);
                        mimeType = cur.getString(mimeTypeColumn);
                        imageFile = new File(thumbData);
                        path = thumbData;
                        mf.setFilePath(imageFile.getPath());
                    }
                } else { // file is not in media library
                    path = imageUri.toString().replace("file://", "");
                    imageFile = new File(path);
                    mf.setFilePath(path);
                }

                // check if the file exists
                if (imageFile == null) {
                    mErrorMessage = context.getString(R.string.file_not_found);
                    mIsMediaError = true;
                    return null;
                }

                if (TextUtils.isEmpty(mimeType)) {
                    mimeType = getMediaFileMimeType(imageFile, true);
                }
                String fileName = getMediaFileName(imageFile, mimeType);
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileName).toLowerCase();

                ImageHelper ih = new ImageHelper();
                orientation = ih.getExifOrientation(path, orientation);

                String resizedPictureURL = null;

                // We need to upload a resized version of the picture when the blog settings != original size, or when
                // the user has selected a smaller size for the current picture in the picture settings screen
                // We won't resize gif images to keep them awesome.
                boolean shouldUploadResizedVersion = false;
                // If it's not a gif and blog don't keep original size, there is a chance we need to resize
                if (!mimeType.equals("image/gif") && !blog.getMaxImageWidth().equals("Original Size")) {
                    //check the picture settings
                    int pictureSettingWidth = mf.getWidth();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, options);
                    int imageHeight = options.outHeight;
                    int imageWidth = options.outWidth;
                    int[] dimensions = {imageWidth, imageHeight};
                    if (dimensions[0] != 0 && dimensions[0] != pictureSettingWidth) {
                        shouldUploadResizedVersion = true;
                    }
                }

                if (shouldUploadResizedVersion) {
                    // create resized picture
                    int rotation;
                    try {
                        rotation = (TextUtils.isEmpty(orientation) ? 0 : Integer.valueOf(orientation));
                    } catch (NumberFormatException e) {
                        rotation = 0;
                    }
                    byte[] bytes = ih.createThumbnailFromUri(context, imageUri, mf.getWidth(), fileExtension, rotation);

                    // upload resized picture
                    if (bytes != null && bytes.length > 0) {
                        Map<String, Object> m = new HashMap<String, Object>();

                        m.put("name", fileName);
                        m.put("type", mimeType);
                        m.put("bits", bytes);
                        m.put("overwrite", true);
                        resizedPictureURL = uploadPicture(m, mf, blog);
                        if (resizedPictureURL == null) {
                            AppLog.w(T.POSTS, "failed to upload resized picture");
                            return null;
                        }
                    } else {
                        AppLog.w(T.POSTS, "failed to create resized picture");
                        mErrorMessage = context.getString(R.string.out_of_memory);
                        mIsMediaError = true;
                        return null;
                    }
                }

                String fullSizeUrl = null;
                //Upload the full size picture if "Original Size" is selected in settings, or if 'link to full size' is checked.
                if (!shouldUploadResizedVersion || blog.isFullSizeImage()) {
                    // try to upload the image
                    Map<String, Object> m = new HashMap<String, Object>();
                    m.put("name", fileName);
                    m.put("type", mimeType);
                    m.put("bits", mf);
                    m.put("overwrite", true);

                    fullSizeUrl = uploadPicture(m, mf, blog);
                    if (fullSizeUrl == null)
                        return null;
                }

                String alignment = "";
                switch (mf.getHorizontalAlignment()) {
                    case 0:
                        alignment = "alignnone";
                        break;
                    case 1:
                        alignment = "alignleft";
                        break;
                    case 2:
                        alignment = "aligncenter";
                        break;
                    case 3:
                        alignment = "alignright";
                        break;
                }

                String alignmentCSS = "class=\"" + alignment + " size-full\" ";

                //Check if we uploaded a featured picture that is not added to the post content (normal case)
                if ((fullSizeUrl != null && fullSizeUrl.equalsIgnoreCase(""))
                        ||
                        (resizedPictureURL != null && resizedPictureURL.equalsIgnoreCase(""))) {
                    return ""; //Not featured in post. Do not add to the content.
                }

                if (fullSizeUrl == null && resizedPictureURL != null) {
                    fullSizeUrl = resizedPictureURL;
                } else if (fullSizeUrl != null && resizedPictureURL == null){
                    resizedPictureURL = fullSizeUrl;
                }

                String mediaTitle = TextUtils.isEmpty(mf.getTitle()) ? "" : mf.getTitle();

                content = content + "<a href=\"" + fullSizeUrl + "\"><img title=\"" + mediaTitle + "\" "
                        + alignmentCSS + "alt=\"image\" src=\"" + resizedPictureURL + "\" /></a>";

                if (!TextUtils.isEmpty(mf.getCaption())) {
                    content = String.format("[caption id=\"\" align=\"%s\" width=\"%d\" caption=\"%s\"]%s[/caption]",
                            alignment, mf.getWidth(), TextUtils.htmlEncode(mf.getCaption()), content);
                }
            }
            return content;
        }

        private String getMediaFileMimeType(File mediaFile, boolean isImage) {
            String originalFileName = mediaFile.getName().toLowerCase();
            String mimeType = UrlUtils.getUrlMimeType(originalFileName);

            if (TextUtils.isEmpty(mimeType)) {
                try {
                    String filePathForGuessingMime = mediaFile.getPath().contains("://") ? mediaFile.getPath() : "file://"+mediaFile.getPath();
                    URL urlForGuessingMime = new URL(filePathForGuessingMime);
                    URLConnection uc = urlForGuessingMime.openConnection();
                    String guessedContentType = uc.getContentType(); //internally calls guessContentTypeFromName(url.getFile()); and guessContentTypeFromStream(is);
                    // check if returned "content/unknown"
                    if (!TextUtils.isEmpty(guessedContentType) && !guessedContentType.equals("content/unknown")) {
                        mimeType = guessedContentType;
                    }
                } catch (MalformedURLException e) {
                    AppLog.e(T.API, "MalformedURLException while trying to guess the content type for the file here " + mediaFile.getPath() +" with URLConnection", e);
                }
                catch (IOException e) {
                    AppLog.e(T.API, "Error while trying to guess the content type for the file here " + mediaFile.getPath() +" with URLConnection", e);
                }
            }

            // No mimeType yet? Try to decode the image get the mimeType from there
            if (isImage && TextUtils.isEmpty(mimeType)) {
                try {
                    DataInputStream inputStream = new DataInputStream(new FileInputStream(mediaFile));
                    String mimeTypeFromStream = MediaUtils.getMimeTypeOfInputStream(inputStream);
                    if (!TextUtils.isEmpty(mimeTypeFromStream)) {
                        mimeType = mimeTypeFromStream;
                    }
                    inputStream.close();
                } catch (FileNotFoundException e) {
                    AppLog.e(T.API, "FileNotFoundException while trying to guess the content type for the file " + mediaFile.getPath(), e);
                } catch (IOException e) {
                    AppLog.e(T.API, "IOException while trying to guess the content type for the file " + mediaFile.getPath(), e);
                }
            }

            if (TextUtils.isEmpty(mimeType)) {
                mimeType = "";
            } else {
                if (mimeType.equalsIgnoreCase("video/mp4v-es")) { //Fixes #533. See: http://tools.ietf.org/html/rfc3016
                    mimeType = "video/mp4";
                }
            }

            return mimeType;
        }

        private String getMediaFileName(File mediaFile, String mimeType) {
            String originalFileName = mediaFile.getName().toLowerCase();
            String extension = MimeTypeMap.getFileExtensionFromUrl(originalFileName);
            if (!TextUtils.isEmpty(extension))  //File name already has the extension in it
                return originalFileName;

            if (!TextUtils.isEmpty(mimeType)) { //try to get the extension from mimeType
                MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
                String fileExtensionFromMimeType = mimeTypeMap.getExtensionFromMimeType(mimeType);
                if (!TextUtils.isEmpty(fileExtensionFromMimeType)) {
                    originalFileName += "." + fileExtensionFromMimeType.toLowerCase();
                } else {
                    //We're still without an extension - split the mime type and retrieve it
                   String[] split = mimeType.split("/");
                   String guessedExt = split.length > 1 ? split[1] : split[0];
                   originalFileName += "." + guessedExt;
                }
            } else {
                //No mimetype and no extension!!
                AppLog.e(T.API, "No mimetype and no extension for " + mediaFile.getPath());
            }

            return originalFileName;
        }

        private String uploadPicture(Map<String, Object> pictureParams, MediaFile mf, Blog blog) {
            XMLRPCClientInterface client = XMLRPCFactory.instantiate(blog.getUri(), blog.getHttpuser(),
                    blog.getHttppassword());

            // create temporary upload file
            File tempFile;
            try {
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(mf.getFileName());
                tempFile = createTempUploadFile(fileExtension);
            } catch (IOException e) {
                mIsMediaError = true;
                mErrorMessage = context.getString(R.string.file_not_found);
                return null;
            }

            Object[] params = { 1, blog.getUsername(), blog.getPassword(), pictureParams };
            Object result = uploadFileHelper(client, params, tempFile);
            if (result == null) {
                mIsMediaError = true;
                return null;
            }

            Map<?, ?> contentHash = (HashMap<?, ?>) result;
            String pictureURL = contentHash.get("url").toString();

            if (mf.isFeatured()) {
                try {
                    if (contentHash.get("id") != null) {
                        featuredImageID = Integer.parseInt(contentHash.get("id").toString());
                        if (!mf.isFeaturedInPost())
                            return "";
                    }
                } catch (NumberFormatException e) {
                    AppLog.e(T.POSTS, e);
                }
            }

            return pictureURL;
        }

        private Object uploadFileHelper(XMLRPCClientInterface client, Object[] params, File tempFile) {
            try {
                return client.call("wp.uploadFile", params, tempFile);
            } catch (XMLRPCException e) {
                AppLog.e(T.API, e);
                mErrorMessage = context.getResources().getString(R.string.error_media_upload) + ": " + e.getMessage();
                return null;
            } catch (IOException e) {
                AppLog.e(T.API, e);
                mErrorMessage = context.getResources().getString(R.string.error_media_upload) + ": " + e.getMessage();
                return null;
            } catch (XmlPullParserException e) {
                AppLog.e(T.API, e);
                mErrorMessage = context.getResources().getString(R.string.error_media_upload) + ": " + e.getMessage();
                return null;
            } finally {
                // remove the temporary upload file now that we're done with it
                if (tempFile != null && tempFile.exists())
                    tempFile.delete();
            }
        }
    }

    private File createTempUploadFile(String fileExtension) throws IOException {
        return File.createTempFile("wp-", fileExtension, context.getCacheDir());
    }
}
