/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.ex8ca.dogo.notes;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receives approved actions from Dogo over a Messenger and replies to them.
 *
 * Contract (see docs/plugins.md): Dogo binds with action
 * app.dogo.launcher.PLUGIN and sends a Message with what = 1, data "action"
 * and "params" (a JSON string), and replyTo set. Reply with what = 0 for
 * success or 1 for an error, and a "message" string for the chat.
 */
public class NotesActionService extends Service {
    private static final int MSG_RUN_ACTION = 1;
    private static final int RESULT_OK = 0;
    private static final int RESULT_ERROR = 1;

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private boolean handle(Message msg) {
        if (msg.what != MSG_RUN_ACTION || msg.replyTo == null) return false;
        String action = msg.getData().getString("action");
        String paramsJson = msg.getData().getString("params", "{}");
        if (!"save_note".equals(action)) {
            reply(msg.replyTo, RESULT_ERROR, "Notes doesn't know \"" + action + "\".");
            return true;
        }
        try {
            String text = new JSONObject(paramsJson).optString("text").trim();
            if (text.isEmpty()) {
                reply(msg.replyTo, RESULT_ERROR, "The note was empty.");
                return true;
            }
            File file = new File(getFilesDir(), "notes.txt");
            try (FileWriter out = new FileWriter(file, true)) {
                out.write(text + "\n");
            }
            reply(msg.replyTo, RESULT_OK, "Saved the note: " + text);
        } catch (JSONException | IOException e) {
            reply(msg.replyTo, RESULT_ERROR, "Couldn't save: " + e.getMessage());
        }
        return true;
    }

    private static void reply(Messenger to, int code, String message) {
        Message answer = Message.obtain(null, code);
        Bundle data = new Bundle();
        data.putString("message", message);
        answer.setData(data);
        try {
            to.send(answer);
        } catch (RemoteException ignored) {
            // Dogo went away; nothing to tell.
        }
    }
}
