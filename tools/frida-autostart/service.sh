#!/system/bin/sh
FRIDA=/data/local/tmp/frida-server
sleep 5
pkill -f frida-server 2>/dev/null
if [ -x "$FRIDA" ]; then
    chmod 755 "$FRIDA"
    "$FRIDA" -l 0.0.0.0:27042 >> /data/local/tmp/frida.log 2>&1 &
    echo "[frida-autostart] started pid=$!" >> /data/local/tmp/frida.log
else
    echo "[frida-autostart] not found: $FRIDA" >> /data/local/tmp/frida.log
fi
