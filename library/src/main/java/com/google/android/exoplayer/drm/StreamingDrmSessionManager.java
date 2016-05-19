/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.drm;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.Util;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.DeniedByServerException;
import android.media.MediaCrypto;
import android.media.MediaDrm;
import android.media.MediaDrm.KeyRequest;
import android.media.MediaDrm.OnEventListener;
import android.media.MediaDrm.ProvisionRequest;
import android.media.NotProvisionedException;
import android.media.UnsupportedSchemeException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.util.HashMap;
import java.util.UUID;

/**
 * A base class for {@link DrmSessionManager} implementations that support streaming playbacks
 * using {@link MediaDrm}.
 */
@TargetApi(18)
public class StreamingDrmSessionManager implements DrmSessionManager {

  /**
   * Interface definition for a callback to be notified of {@link StreamingDrmSessionManager}
   * events.
   */
  public interface EventListener {

    /**
     * Invoked each time keys are loaded.
     */
    void onDrmKeysLoaded();

    /**
     * Invoked when a drm error occurs.
     *
     * @param e The corresponding exception.
     */
    void onDrmSessionManagerError(Exception e);

  }

  /**
   * The key to use when passing CustomData to a PlayReady instance in an optional parameter map.
   */
  public static final String PLAYREADY_CUSTOM_DATA_KEY = "PRCustomData";

  private static final int MSG_PROVISION = 0;
  private static final int MSG_KEYS = 1;

  private final Handler eventHandler;
  private final EventListener eventListener;
  private final MediaDrm mediaDrm;
  private final HashMap<String, String> optionalKeyRequestParameters;

  /* package */ final MediaDrmCallback callback;
  /* package */ final UUID uuid;

  /* package */ MediaDrmHandler mediaDrmHandler;
  /* package */ PostResponseHandler postResponseHandler;

  private Looper playbackLooper;
  private HandlerThread requestHandlerThread;
  private Handler postRequestHandler;

  private int openCount;
  private boolean provisioningInProgress;
  private int state;
  private MediaCrypto mediaCrypto;
  private Exception lastException;
  private SchemeData schemeData;
  private byte[] sessionId;

  /**
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   */
  public StreamingDrmSessionManager(MediaDrmCallback callback,
      HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
      EventListener eventListener) {
    this.callback = callback;
    this.optionalKeyRequestParameters = optionalKeyRequestParameters;
    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    uuid = callback.getUuid();
    MediaDrm mediaDrm = null;
    try {
      mediaDrm = new MediaDrm(uuid);
      mediaDrm.setOnEventListener(new MediaDrmEventListener());
      state = STATE_CLOSED;
    } catch (UnsupportedSchemeException e) {
      lastException = new UnsupportedDrmException(
          UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME, e);
      state = STATE_ERROR;
    } catch (Exception e) {
      lastException = new UnsupportedDrmException(
          UnsupportedDrmException.REASON_INSTANTIATION_ERROR, e);
      state = STATE_ERROR;
    } finally {
      this.mediaDrm = mediaDrm;
    }
  }

  @Override
  public final int getState() {
    return state;
  }

  @Override
  public final MediaCrypto getMediaCrypto() {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      throw new IllegalStateException();
    }
    return mediaCrypto;
  }

  @Override
  public boolean requiresSecureDecoderComponent(String mimeType) {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      throw new IllegalStateException();
    }
    return mediaCrypto.requiresSecureDecoderComponent(mimeType);
  }

  @Override
  public final Exception getError() {
    return state == STATE_ERROR ? lastException : null;
  }

  /**
   * Provides access to {@link MediaDrm#getPropertyString(String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The key to request.
   * @return The retrieved property.
   */
  public final String getPropertyString(String key) {
    return mediaDrm.getPropertyString(key);
  }

  /**
   * Provides access to {@link MediaDrm#setPropertyString(String, String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyString(String key, String value) {
    mediaDrm.setPropertyString(key, value);
  }

  /**
   * Provides access to {@link MediaDrm#getPropertyByteArray(String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The key to request.
   * @return The retrieved property.
   */
  public final byte[] getPropertyByteArray(String key) {
    return mediaDrm.getPropertyByteArray(key);
  }

  /**
   * Provides access to {@link MediaDrm#setPropertyByteArray(String, byte[])}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyByteArray(String key, byte[] value) {
    mediaDrm.setPropertyByteArray(key, value);
  }

  @Override
  public void open(Looper playbackLooper, DrmInitData drmInitData) {
    Assertions.checkState(this.playbackLooper == null || this.playbackLooper == playbackLooper);
    if (++openCount != 1) {
      return;
    }

    if (this.playbackLooper == null) {
      this.playbackLooper = playbackLooper;
      mediaDrmHandler = new MediaDrmHandler(playbackLooper);
      postResponseHandler = new PostResponseHandler(playbackLooper);
    }

    requestHandlerThread = new HandlerThread("DrmRequestHandler");
    requestHandlerThread.start();
    postRequestHandler = new PostRequestHandler(requestHandlerThread.getLooper());

    schemeData = drmInitData.get(uuid);
    if (schemeData == null) {
      onError(new IllegalStateException("Media does not support uuid: " + uuid));
      return;
    }
    if (Util.SDK_INT < 21) {
      // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
      byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(schemeData.data, C.WIDEVINE_UUID);
      if (psshData == null) {
        // Extraction failed. schemeData isn't a Widevine PSSH atom, so leave it unchanged.
      } else {
        schemeData = new SchemeData(C.WIDEVINE_UUID, schemeData.mimeType, psshData);
      }
    }
    state = STATE_OPENING;
    openInternal(true);
  }

  @Override
  public void close() {
    if (--openCount != 0) {
      return;
    }
    state = STATE_CLOSED;
    provisioningInProgress = false;
    mediaDrmHandler.removeCallbacksAndMessages(null);
    postResponseHandler.removeCallbacksAndMessages(null);
    postRequestHandler.removeCallbacksAndMessages(null);
    postRequestHandler = null;
    requestHandlerThread.quit();
    requestHandlerThread = null;
    schemeData = null;
    mediaCrypto = null;
    lastException = null;
    if (sessionId != null) {
      mediaDrm.closeSession(sessionId);
      sessionId = null;
    }
  }

  private void openInternal(boolean allowProvisioning) {
    try {
      sessionId = mediaDrm.openSession();
      mediaCrypto = new MediaCrypto(uuid, sessionId);
      state = STATE_OPENED;
      postKeyRequest();
    } catch (NotProvisionedException e) {
      if (allowProvisioning) {
        postProvisionRequest();
      } else {
        onError(e);
      }
    } catch (Exception e) {
      onError(e);
    }
  }

  private void postProvisionRequest() {
    if (provisioningInProgress) {
      return;
    }
    provisioningInProgress = true;
    ProvisionRequest request = mediaDrm.getProvisionRequest();
    postRequestHandler.obtainMessage(MSG_PROVISION, request).sendToTarget();
  }

  private void onProvisionResponse(Object response) {
    provisioningInProgress = false;
    if (state != STATE_OPENING && state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      // This event is stale.
      return;
    }

    if (response instanceof Exception) {
      onError((Exception) response);
      return;
    }

    try {
      mediaDrm.provideProvisionResponse((byte[]) response);
      if (state == STATE_OPENING) {
        openInternal(false);
      } else {
        postKeyRequest();
      }
    } catch (DeniedByServerException e) {
      onError(e);
    }
  }

  private void postKeyRequest() {
    KeyRequest keyRequest;
    try {
      keyRequest = mediaDrm.getKeyRequest(sessionId, schemeData.data, schemeData.mimeType,
          MediaDrm.KEY_TYPE_STREAMING, optionalKeyRequestParameters);
      postRequestHandler.obtainMessage(MSG_KEYS, keyRequest).sendToTarget();
    } catch (NotProvisionedException e) {
      onKeysError(e);
    }
  }

  private void onKeyResponse(Object response) {
    if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
      // This event is stale.
      return;
    }

    if (response instanceof Exception) {
      onKeysError((Exception) response);
      return;
    }

    try {
      mediaDrm.provideKeyResponse(sessionId, (byte[]) response);
      state = STATE_OPENED_WITH_KEYS;
      if (eventHandler != null && eventListener != null) {
        eventHandler.post(new Runnable() {
          @Override
          public void run() {
            eventListener.onDrmKeysLoaded();
          }
        });
      }
    } catch (Exception e) {
      onKeysError(e);
    }
  }

  private void onKeysError(Exception e) {
    if (e instanceof NotProvisionedException) {
      postProvisionRequest();
    } else {
      onError(e);
    }
  }

  private void onError(final Exception e) {
    lastException = e;
    if (eventHandler != null && eventListener != null) {
      eventHandler.post(new Runnable() {
        @Override
        public void run() {
          eventListener.onDrmSessionManagerError(e);
        }
      });
    }
    if (state != STATE_OPENED_WITH_KEYS) {
      state = STATE_ERROR;
    }
  }

  @SuppressLint("HandlerLeak")
  private class MediaDrmHandler extends Handler {

    public MediaDrmHandler(Looper looper) {
      super(looper);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleMessage(Message msg) {
      if (openCount == 0 || (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS)) {
        return;
      }
      switch (msg.what) {
        case MediaDrm.EVENT_KEY_REQUIRED:
          postKeyRequest();
          return;
        case MediaDrm.EVENT_KEY_EXPIRED:
          state = STATE_OPENED;
          onError(new KeysExpiredException());
          return;
        case MediaDrm.EVENT_PROVISION_REQUIRED:
          state = STATE_OPENED;
          postProvisionRequest();
          return;
      }
    }

  }

  private class MediaDrmEventListener implements OnEventListener {

    @Override
    public void onEvent(MediaDrm md, byte[] sessionId, int event, int extra, byte[] data) {
      mediaDrmHandler.sendEmptyMessage(event);
    }

  }

  @SuppressLint("HandlerLeak")
  private class PostResponseHandler extends Handler {

    public PostResponseHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
      switch (msg.what) {
        case MSG_PROVISION:
          onProvisionResponse(msg.obj);
          return;
        case MSG_KEYS:
          onKeyResponse(msg.obj);
          return;
      }
    }

  }

  @SuppressLint("HandlerLeak")
  private class PostRequestHandler extends Handler {

    public PostRequestHandler(Looper backgroundLooper) {
      super(backgroundLooper);
    }

    @Override
    public void handleMessage(Message msg) {
      Object response;
      try {
        switch (msg.what) {
          case MSG_PROVISION:
            response = callback.executeProvisionRequest(uuid, (ProvisionRequest) msg.obj);
            break;
          case MSG_KEYS:
            response = callback.executeKeyRequest(uuid, (KeyRequest) msg.obj);
            break;
          default:
            throw new RuntimeException();
        }
      } catch (Exception e) {
        response = e;
      }
      postResponseHandler.obtainMessage(msg.what, response).sendToTarget();
    }

  }

}
