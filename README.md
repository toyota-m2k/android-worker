Foreground Workerを使う場合
AndroidManifest.xmlに以下を追加してください。
これがないと、Foreground Workerが動作しません（強制終了します）。

```xml
<manifest>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
    <application>
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />
    </application>
</manifest>
```

Foreground Workerでは通知を使用しますので、通知のパーミッション（android.permission.POST_NOTIFICATIONS）設定も必要です。
AndroidManifest.xml への記載と、実行時のパーミッション要求を実装してください。
（少なくともAndroid16まで、パーミッションがなくても、Foreground Workerは動作するようですが、本来の要件を満たすためパーミッションの設定を推奨します。）
