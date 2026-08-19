# Maven 发布与依赖维护

坐标：`luowei.player_block_status:player-block-status:<mod_version>`  
版本号在 `gradle.properties` 的 `mod_version`（当前 `1.0.13`）。

---

## 本机如何发布

在本库根目录执行：

```bat
.\gradlew.bat publishToMavenLocal
```

构件会写到本机 `~/.m2/repository`。

消费方 `build.gradle`：

```gradle
repositories {
	mavenLocal()
}

dependencies {
	modImplementation 'luowei.player_block_status:player-block-status:1.0.13'
}
```

`fabric.mod.json` 增加：

```json
"depends": { "player-block-status": ">=1.0.13" }
```

---

## 消费方如何更新

本库发布新版本后：

1. 将 `build.gradle` 中的依赖版本改为新的 `mod_version`。
2. 同步调整 `fabric.mod.json` 的 `depends`（例如 `">=1.0.8"`）。
3. 重新编译/运行消费方模组。
4. 游戏运行时仍需把本库对应版本的 JAR 放进 `mods/`（编译依赖更新不会自动替换运行时模组）。

若本库**未升版本号**、只是覆盖了本机同一坐标，消费方坐标不用改，但需强制刷新：

```bat
.\gradlew.bat build --refresh-dependencies
```

---

## 后续网络如何发布

1. 在本库 `build.gradle` 的 `publishing.repositories` 里追加远程仓库（GitHub Packages、自建 Maven 等），并按该平台配置凭据。  
2. 执行对应的 `publish`（具体任务名以该仓库文档为准）。  
3. 消费方把 `mavenLocal()` 换成远程地址，依赖坐标不变，只改版本号。

---

## 本地如何维护

1. 改本库代码。  
2. 有对外改动时升高 `mod_version`。  
3. 再执行 `.\gradlew.bat publishToMavenLocal`。  
4. 消费方按上文「消费方如何更新」对齐版本后重新编译/运行。
