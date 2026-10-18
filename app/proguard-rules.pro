# Libraries bring their own keep rules; nothing app-specific is needed yet.
# Paho creates its logger by class name at runtime.
-keep class org.eclipse.paho.client.mqttv3.logging.JSR47Logger { *; }
