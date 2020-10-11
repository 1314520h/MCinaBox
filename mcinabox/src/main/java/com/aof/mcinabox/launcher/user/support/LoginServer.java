package com.aof.mcinabox.launcher.user.support;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.aof.mcinabox.MainActivity;
import com.aof.mcinabox.R;
import com.aof.mcinabox.launcher.setting.support.SettingJson;
import com.aof.mcinabox.launcher.user.UserManager;
import com.aof.utils.dialog.DialogUtils;
import com.aof.utils.dialog.support.DialogSupports;
import com.aof.utils.dialog.support.TaskDialog;
import com.google.gson.Gson;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Objects;
import java.util.UUID;

public class LoginServer {
    private Context mContext;
    private final static String TAG = "LoginServer";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();
    public static final String MOJANG_URL = "https://authserver.mojang.com";

    private String url;
    private String username;
    private String password;
    private UUID clientToken;
    private boolean isLogining;

    private SettingJson.Account account;

    public LoginServer(String url, Context context){
        this(url, new SettingJson().new Account(),context);
    }

    public LoginServer(String url) {
        this(url,MainActivity.CURRENT_ACTIVITY);
    }

    public LoginServer(String url, SettingJson.Account account, Context context) {
        this.mContext = context;
        if(url == null || url.equals("")) url = MOJANG_URL;
        else if (!url.startsWith("http")) url = "https://".concat(url);
        this.url = url;
        this.account = account;
        isLogining = false;
    }

    private void verifyServer(String url) {
        account.setApiUrl(url);
        Request request = new Request.Builder().url(url).build();
        try {
            client.newCall(request).enqueue(verifyServerResponse);
        }catch (Exception e) {
            output("Error", e.getMessage());
        }
    }

    public String refresh(String accessToken) {
        return null;
    }

    public void login(String username, String password, UUID clientToken) {
        whenWaiting(1);
        this.username = username;
        this.password = password;
        this.clientToken = clientToken;
        isLogining = true;
        if(url.equals(MOJANG_URL))  {
            account.setType(SettingJson.USER_TYPE_ONLINE);
            login();
        }
        else {
            account.setType(SettingJson.USER_TYPE_EXTERNAL);
            verifyServer(url);
        }
    }

    TaskDialog mDialog;
    private void whenWaiting(int i){
        if(mDialog == null){
            mDialog = DialogUtils.createTaskDialog(mContext,mContext.getString(R.string.tips_logging),"",false);
        }
        switch (i){
            case 1:
                if(!mDialog.isShowing()){
                    mDialog.show();
                }
                break;
            case 2:
                if(mDialog.isShowing()){
                    mDialog.dismiss();
                }
                break;
        }
    }

    private void login() {
        AuthenticateRequest userdata = new AuthenticateRequest(username, password, clientToken, "Minecraft", 1);
        RequestBody body = RequestBody.create(JSON, gson.toJson(userdata));
        Request request = new Request.Builder().url(url + (url.equals(MOJANG_URL)?"/authenticate":"/authserver/authenticate")).post(body).build();
        try {
            client.newCall(request).enqueue(loginResponse);
        }catch (Exception e) {
            output("Error", e.getMessage());
        }
    }

    private okhttp3.Callback verifyServerResponse = new okhttp3.Callback() {
        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
            Message msg = new Message();
            Bundle data = new Bundle();
            data.putString("Type", "Error");
            data.putString("Result", e.getMessage());
            msg.setData(data);
            handler.sendMessage(msg);
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) {
            Message msg = new Message();
            Bundle data = new Bundle();

            try {
                if(response.toString().contains("x-authlib-injector-api-location")) {
                    verifyServer(response.request().url().toString());
                    data.putString("Type", "VerifyServer");
                    data.putString("Result", MainActivity.CURRENT_ACTIVITY.getResources().getString(R.string.tips_redirecting));
                }else {
                    if(response.code() == 200) data.putString("Type", "VerifyServer");
                    else data.putString("Type", "Error");
                    data.putString("Result", Objects.requireNonNull(response.body()).string());
                }
            }catch (Exception e) {
                data.putString("Type", "Error");
                data.putString("Result", e.getMessage());
            }

            msg.setData(data);
            handler.sendMessage(msg);
        }
    };

    private okhttp3.Callback loginResponse = new okhttp3.Callback() {
        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
            isLogining = false;
            Message msg = new Message();
            Bundle data = new Bundle();
            data.putString("Type", "Error");
            data.putString("Result", e.getMessage());
            msg.setData(data);
            handler.sendMessage(msg);
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) {
            isLogining = false;
            Message msg = new Message();
            Bundle data = new Bundle();

            try {
                String result = Objects.requireNonNull(response.body()).string();
                if(response.code() == 200) {
                    data.putString("Type", "Login");
                    data.putString("Result", result);
                }else {
                    ErrorResponse error = gson.fromJson(result, ErrorResponse.class);
                    data.putString("Type", "Error");
                    data.putString("Result", error.errorMessage);
                }
            }catch (Exception e) {
                data.putString("Type", "Error");
                data.putString("Result", e.getMessage());
            }

            msg.setData(data);
            handler.sendMessage(msg);
        }
    };

    @SuppressLint("HandlerLeak")
    private Handler handler = new  Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Bundle data = msg.getData();
            String type = data.getString("Type");
            String result = data.getString("Result");
            output(type, result);
        }
    };

    private void output(String type, String result) {
        if(result != null)
        switch (type) {
            case "VerifyServer":
                AuthlibResponse authlibResponse = gson.fromJson(result, AuthlibResponse.class);
                account.setServerName(authlibResponse.meta.serverName);
                account.setApiMeta(Base64.encodeToString(result.getBytes(), Base64.DEFAULT));
                if(isLogining) login();
                break;
            case "Login":
                final AuthenticateResponse authenticateResponse = gson.fromJson(result, AuthenticateResponse.class);
                if(authenticateResponse.availableProfiles == null || authenticateResponse.availableProfiles.length == 0){
                    DialogUtils.createSingleChoiceDialog(mContext,mContext.getString(R.string.title_error),mContext.getString(R.string.tips_no_roles_in_current_account),mContext.getString(R.string.title_ok),null);
                    return;
                }
                if(authenticateResponse.availableProfiles.length != 1){
                    String[] names = new String[authenticateResponse.availableProfiles.length];
                    for(int a = 0; a < authenticateResponse.availableProfiles.length; a++){
                        Log.e("LoginServer",authenticateResponse.availableProfiles[a].name);
                        names[a] = authenticateResponse.availableProfiles[a].name;
                    }
                    DialogUtils.createItemsChoiceDialog(mContext,mContext.getString(R.string.title_choice),null,mContext.getString(R.string.title_cancel),false,names,new DialogSupports(){
                        @Override
                        public void runWhenItemsSelected(int pos) {
                            super.runWhenItemsSelected(pos);
                            account.setAccessToken(authenticateResponse.accessToken);
                            account.setUuid(authenticateResponse.availableProfiles[pos].id);
                            account.setUsername(authenticateResponse.availableProfiles[pos].name);
                            account.setSelected(false);
                            UserManager.addAccount(MainActivity.Setting, account);
                        }
                    });
                }else{
                    account.setAccessToken(authenticateResponse.accessToken);
                    account.setUuid(authenticateResponse.availableProfiles[0].id);
                    account.setUsername(authenticateResponse.availableProfiles[0].name);
                    account.setSelected(false);
                    UserManager.addAccount(MainActivity.Setting, account);
                }
                break;
            case "Error":
                DialogUtils.createSingleChoiceDialog(mContext,mContext.getString(R.string.title_error),String.format(mContext.getString(R.string.tips_error),result),mContext.getString(R.string.title_ok),null);
                break;
        }
        whenWaiting(2);
    }
}