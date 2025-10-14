/* This is used in both iOS and Android, so make sure any changes are compatible with both */
const _global = typeof window !== 'undefined' ? window : globalThis;
window = _global; // For compatibility with existing code that expects `window`
_global.onerror = (message, source, lineno, colno, error) => {
    _Pebble.onError(message, source, lineno, colno, error);
};
_global.onunhandledrejection = (event) => {
    _Pebble.onUnhandledRejection(event.reason);
}
_global.navigator = _global.navigator || {};
_global._PebbleGeoCB = {
    _requestCallbacks: new Map(),
    _watchCallbacks: new Map(),
    _resultGetSuccess: (id, latitude, longitude, accuracy, altitude, heading, speed) => {
        const callback = _PebbleGeoCB._requestCallbacks.get(id);
        if (callback && callback.success) {
            _PebbleGeoCB._requestCallbacks.delete(id);
            callback.success({ coords: { latitude, longitude, accuracy, altitude, heading, speed } });
        }
    },
    _resultGetError: (id, message) => {
        const callback = _PebbleGeoCB._requestCallbacks.get(id);
        if (callback && callback.error) {
            _PebbleGeoCB._requestCallbacks.delete(id);
            callback.error({ message, code: 1 });
        }
    },
    _resultWatchSuccess: (id, latitude, longitude, accuracy, altitude, heading, speed) => {
        const callback = _PebbleGeoCB._watchCallbacks.get(id);
        if (callback && callback.success) {
            callback.success({ coords: { latitude, longitude, accuracy, altitude, heading, speed } });
        }
    },
    _resultWatchError: (id, message) => {
        const callback = _PebbleGeoCB._watchCallbacks.get(id);
        if (callback && callback.error) {
            callback.error({ message, code: 1 });
        }
    }
};
navigator.geolocation.getCurrentPosition = (success, error, options) => {
    const id = _PebbleGeo.getRequestCallbackID();
    _PebbleGeoCB._requestCallbacks.set(id, { success, error });
    _PebbleGeo.getCurrentPosition(id);
};
navigator.geolocation.watchPosition = (success, error, options) => {
    const id = _PebbleGeo.getWatchCallbackID();
    _PebbleGeoCB._watchCallbacks.set(id, { success, error });
    _PebbleGeo.watchPosition(id);
    return id;
};
navigator.geolocation.clearWatch = (id) => {
    _PebbleGeo.clearWatch(id);
    if (_PebbleGeoCB._watchCallbacks.has(id)) {
        _PebbleGeoCB._watchCallbacks.delete(id);
    }
};

((global) => {
    const oldConsole = {
        log: console.log,
        warn: console.warn,
        error: console.error,
        info: console.info,
        debug: console.debug,
    }
    const sendLog = (level, ...args) => {
        // build args into a single string
        const message = args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' ');
        const traceback = new Error().stack;
        const callerLine = traceback ? traceback.split('\n')[3].trim() : null;
        _Pebble.onConsoleLog(level, message, callerLine);
    }
    console.log = (...args) => {
        oldConsole.log.apply(console, args);
        sendLog('log', ...args);
    }
    console.warn = (...args) => {
        oldConsole.warn.apply(console, args);
        sendLog('warn', ...args);
    }
    console.error = (...args) => {
        oldConsole.error.apply(console, args);
        sendLog('error', ...args);
    }
    console.info = (...args) => {
        oldConsole.info.apply(console, args);
        sendLog('info', ...args);
    }
    console.debug = (...args) => {
        oldConsole.debug.apply(console, args);
        sendLog('debug', ...args);
    }
    console.trace = (...args) => {
        oldConsole.trace.apply(console, args);
        const message = args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' ');
        const traceback = new Error().stack;
        const tracebackWithoutThis = traceback ? traceback.split('\n').slice(2).join('\n') : null;
        _Pebble.onConsoleLog('trace', message, "\n"+tracebackWithoutThis);
    }
    console.assert = (condition, ...args) => {
        if (!condition) {
            const message = "Assertion failed:" + args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' ');
            const traceback = new Error().stack;
            const caller = traceback ? traceback.split('\n')[2].trim() : null;
            _Pebble.onConsoleLog('assert', message, caller);
        }
    }
    const PebbleEventTypes = {
        READY: 'ready',
        SHOW_CONFIGURATION: 'showConfiguration',
        WEBVIEW_OPENED: 'webviewopened',
        WEBVIEW_CLOSED: 'webviewclosed',
        APP_MESSAGE: 'appmessage',
        APP_MESSAGE_ACK: 'appmessage_ack',
        APP_MESSAGE_NACK: 'appmessage_nack',
        GET_TIMELINE_TOKEN_SUCCESS: 'getTimelineTokenSuccess',
        GET_TIMELINE_TOKEN_FAILURE: 'getTimelineTokenFailure',
    };
    Object.freeze(PebbleEventTypes);
    const DEFAULT_TIMEOUT = 5000; // 5 seconds

    class PebbleEventListener {
        constructor() {
            this.events = new Map();
            this._eventInitializers = {};
        }

        addEventListener(type, callback, useCapture /* ignored */) {
            if (typeof callback !== 'function') {
                console.warn(`Pebble JS Bridge: addEventListener called with non-function callback for type "${type}"`);
                return;
            }

            if (!this.events.has(type)) {
                this.events.set(type, new Set());

                // Call the event initializer if this is the first time
                if (typeof this._eventInitializers[type] === 'function') {
                    try {
                        this._eventInitializers[type]();
                    } catch(e) {
                        console.error(`Pebble JS Bridge: Error in event initializer for "${type}"`, e);
                    }
                }
            }
            this.events.get(type).add(callback);
        }

        removeEventListener(type, callback) {
            const listeners = this.events.get(type);
            if (!listeners) {
                return;
            }
            listeners.delete(callback);
            if (listeners.size === 0) {
                this.events.delete(type);
            }
        }

        dispatchEvent(event) {
            const listeners = this.events.get(event.type);
            if (!listeners || listeners.size === 0) {
                return false; // Indicate no listeners were called
            }

            // Clone the listeners to avoid modifying the set while iterating
            const listenersCopy = [...listeners];
            let allSucceeded = true;

            listenersCopy.forEach(listener => {
                try {
                    const removeListener = listener(event);
                    if (removeListener === true) {
                        listeners.delete(listener);
                    }
                } catch (e) {
                    console.error(`Pebble JS Bridge: Error in listener for event "${event.type}"`, e);
                    allSucceeded = false;
                }
            });
             if (listeners.size === 0) {
                this.events.delete(event.type);
            }
            return allSucceeded;
        }
    }

    const pebbleEventHandler = new PebbleEventListener();
    const appMessageAckCallbacks = new Map();
    const appMessageNackCallbacks = new Map();

    const dispatchPebbleEvent = (type, detail = {}) => {
        const event = {type: type, bubbles: false, cancelable: false};
        Object.assign(event, detail);
        return pebbleEventHandler.dispatchEvent(event);
    };
    const removeAppMessageCallbacksForTransactionId = (tid) => {
        const ackCallback = appMessageAckCallbacks.get(tid);
        if (ackCallback) {
            pebbleEventHandler.removeEventListener('appmessage_ack', ackCallback);
            appMessageAckCallbacks.delete(tid);
        }

        const nackCallback = appMessageNackCallbacks.get(tid);
        if (nackCallback) {
            pebbleEventHandler.removeEventListener('appmessage_nack', nackCallback);
            appMessageNackCallbacks.delete(tid);
        }
    }

    global.signalWebviewOpenedEvent = (data) => {
        dispatchPebbleEvent(PebbleEventTypes.WEBVIEW_OPENED, { opened: data });
    }
    global.signalWebviewClosedEvent = (data) => {
        dispatchPebbleEvent(PebbleEventTypes.WEBVIEW_CLOSED, { response: data });
    }
    global.signalReady = (data) => {
        const success = dispatchPebbleEvent(PebbleEventTypes.READY, { ready: data });
        try {
            _Pebble.privateFnConfirmReadySignal(success);
        } catch (e) {
            console.error("Pebble JS Bridge: Error confirming ready signal", e);
        }
    }
    global.signalNewAppMessageData = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.APP_MESSAGE, { payload });
    }
    global.signalAppMessageAck = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.APP_MESSAGE_ACK, { payload });

        if (payload.transactionId !== undefined) {
            removeAppMessageCallbacksForTransactionId(payload.transactionId);
        }
    }
    global.signalAppMessageNack = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.APP_MESSAGE_NACK, { payload });

        if (payload.transactionId !== undefined) {
            removeAppMessageCallbacksForTransactionId(payload.transactionId);
        }
    }
    global.signalShowConfiguration = () => {
        dispatchPebbleEvent('showConfiguration', {});
        // Legacy event
        dispatchPebbleEvent('settings_webui_allowed', {});
    };
    global.signalTimelineTokenSuccess = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, { payload });
    };
    global.signalTimelineTokenFailure = (data) => {
        const payload = data ? JSON.parse(data) : {};
        dispatchPebbleEvent(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, { payload });
    };

    const PebbleAPI = {
        addEventListener: (type, callback, useCapture) => {
            pebbleEventHandler.addEventListener(type, callback, useCapture);
        },
        removeEventListener: (type, callback) => {
            pebbleEventHandler.removeEventListener(type, callback);
        },
        sendAppMessage: (data, onSuccess, onFailure) => {
            const transactionId = _Pebble.sendAppMessageString(JSON.stringify(data));
            if (transactionId === -1) {
                if (onFailure) {
                    onFailure({error: "Failed to connect to Pebble"});
                }
                return -1;
            }
            if (onSuccess) {
                const callback = (e) => {
                    try {
                        if (e.payload.data.transactionId === transactionId) {
                            onSuccess(e.payload);
                        }
                    } catch (error) {
                        console.error("PKJS Error in app message success callback", error);
                    }
                }
                appMessageAckCallbacks.set(transactionId, callback);
                pebbleEventHandler.addEventListener(PebbleEventTypes.APP_MESSAGE_ACK, callback);
            }
            if (onFailure) {
                const callback = (e) => {
                    try {
                        if (e.payload.data.transactionId === transactionId) {
                            onFailure(e.payload, e.payload.error);
                        }
                    } catch (error) {
                        console.error("PKJS Error in app message failure callback", error);
                    }
                }
                appMessageNackCallbacks.set(transactionId, callback);
                pebbleEventHandler.addEventListener(PebbleEventTypes.APP_MESSAGE_NACK, callback);
            }
            return transactionId;
        },
        getTimelineToken: (onSuccess, onFailure) => {
            const callId = _Pebble.getTimelineTokenAsync();
            const successCallback = (e) => {
                if (e.data.callId === callId) {
                    onSuccess(e.data.userToken);
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, successCallback);
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, failureCallback);
                }
            }
            const failureCallback = (e) => {
                if (e.data.callId === callId) {
                    onFailure();
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, successCallback);
                    pebbleEventHandler.removeEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, failureCallback);
                }
            }
            pebbleEventHandler.addEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_SUCCESS, successCallback);
            pebbleEventHandler.addEventListener(PebbleEventTypes.GET_TIMELINE_TOKEN_FAILURE, failureCallback);
        },
        timelineSubscribe: (token, onSuccess, onFailure) => {
            //TODO
        },
        timelineUnsubscribe: (token, onSuccess, onFailure) => {
            //TODO
        },
        timelineSubscriptions: (onSuccess, onFailure) => {
            //TODO
        },
        getActiveWatchInfo: () => {
            const data = _Pebble.getActivePebbleWatchInfo();
            return data ? JSON.parse(data) : null;
        },
        appGlanceReload: (appGlanceSlices, onSuccess, onFailure) => {
            //TODO
        },
    }
    global.Pebble.addEventListener = PebbleAPI.addEventListener;
    global.Pebble.removeEventListener = PebbleAPI.removeEventListener;
    global.Pebble.sendAppMessage = PebbleAPI.sendAppMessage;
    global.Pebble.getTimelineToken = PebbleAPI.getTimelineToken;
    global.Pebble.timelineSubscribe = PebbleAPI.timelineSubscribe;
    global.Pebble.timelineUnsubscribe = PebbleAPI.timelineUnsubscribe;
    global.Pebble.timelineSubscriptions = PebbleAPI.timelineSubscriptions;
    global.Pebble.getActiveWatchInfo = PebbleAPI.getActiveWatchInfo;
    global.Pebble.appGlanceReload = PebbleAPI.appGlanceReload;

    console.log("Pebble JS Bridge initialized.");
})(_global);