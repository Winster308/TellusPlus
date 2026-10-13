# Tellus Plus → Minecraft 1.20.1 Fabric 移植报告

作者：`port-mc1201` ｜ 模块：`mc1201/` ｜ 日期：2026-10-11

> **v2（实机自查轮）**：本轮发现并修复了一个**会让世界加载崩溃**的数据文件问题（见第 11 节）。
> jar 已重建，**新 SHA256 = `99B1F5B271F92598BA341FAF6C86638EE5914DB32FB510DC0803971C793537B3`**。

---

## 0. 结论摘要（先读这段）

| 项 | 结果 |
|---|---|
| `:mc1201:compileJava`（main 源码集） | **0 错误** |
| `:mc1201:compileClientJava`（client 源码集） | **0 错误** |
| `:mc1201:build -x test` | **BUILD SUCCESSFUL**（含 clean 全量重建复现，1m42s） |
| `Cannot remap` 警告 | **0 条** |
| mixin 双向一致性 | **零差异**（声明 27 = jar 内 27，双向无缺失/无未声明） |
| jar | `mc1201/build/libs/TellusCN-1.20.1-1.0.0.jar`（45,420,155 B） |
| jar SHA256 | `99B1F5B271F92598BA341FAF6C86638EE5914DB32FB510DC0803971C793537B3`（v2，含数据修复） |
| jar 内模组 class | **659 个**（`com/yucareux/*`） |
| **功能正确性** | **未验证 —— 我没有启动过游戏** |
| **静态 schema 审计** | 已做（第 11 节）—— 发现并修复 1 个崩溃级问题 |

**「编译通过」与「功能正确」是两回事。** 本报告里所有「能编译」的说法都有编译器输出为证；所有「能运行」的说法我都**没有**做出。第五节逐条列出未验证项。

**唯一一个未通过的任务**：`:mc1201:test` 有 2 个失败。这两条**不是我引入的**，已用 mc1219 自己的历史测试结果证明 —— 见 4.1。

---

## 1. 目标版本参数（我自己核实过的值）

| 项 | 值 | 核实方式 |
|---|---|---|
| Minecraft | **1.20.1** | 任务目标 |
| Fabric Loader | **0.16.10** | 本机 Gradle 缓存存在该版本 |
| Fabric API | **0.92.8+1.20.1** | 已解析并 remap 成功（`build/loom-cache` 内有完整依赖树） |
| Java | **17** | `options.release = 17`；1.20.1 的 class file 版本 |
| Loom | **1.16.3** | **不是照抄 mc1219 的 1.17.20** — 见下 |
| Mod Menu | **7.2.2** | Modrinth API 实测：1.20.1 线最新 release |
| DistantHorizonsApi | 6.1.0 | `compileOnly`，继承 mc1219 |

### ★ 关于 Loom 版本：mc1219 的 `1.17.20` 在 1.20.1 上**用不了**

我第一版照抄了 `1.17.20`，构建直接失败：

```
Could not resolve net.fabricmc:fabric-loom:1.17.20
  Variant 'runtimeElements' ... attribute 'org.gradle.plugin.api-version' with value '9.5.0'
  and the consumer needed ... '9.4.0'
```

**Loom 与 MC 版本无关，它绑定的是 Gradle 版本。** 我读了本机缓存里各版本 Loom 的 Gradle module metadata：

| Loom | 要求的 Gradle (`org.gradle.plugin.api-version`) |
|---|---|
| 1.15.5 | 9.2.0 |
| **1.16.3** | **9.4.0** ← 本仓库 wrapper 正好是 9.4.0 |
| 1.17.20 / 1.17.21 | 9.5.0 |
| 1.18.1 / 1.18.2 | 9.7.0 |

本仓库 wrapper = **Gradle 9.4.0**，所以选 **Loom 1.16.3**（能接受 9.4.0 的最新版，且已在本机缓存）。

> 附带发现：**mc1219 本身在当前 wrapper 下无法配置**（`-Ptarget=mc1219` 会因同一条 plugin-api 冲突失败）。这是仓库的既有状态，不是我造成的 —— 但可能值得 Lead 关注。

---

## 2. 交付物

```
E:\telluscnplus\upstream\TellusCN-main\mc1201\build\libs\
    TellusCN-1.20.1-1.0.0.jar          45,420,136 bytes
    TellusCN-1.20.1-1.0.0-sources.jar  42,722,825 bytes
```

**SHA256**

```
主 jar          9A8C384D412980AB11738171207F333E6960B07D931F72396FB9FF04BBA31C72
sources jar     见 mc1201/build/libs/（同一构建产出）
```

**jar 内容构成**

| 类别 | 条目数 |
|---|---|
| 总条目 | 1303 |
| `com/yucareux/*`（模组代码） | **659** |
| mixin class | 27 |
| `org/tukaani/*`（打包的 xz） | 133 |
| `assets/` | 447 |
| `data/` | 26 |
| `tellus/`（共享资源） | 13 |

必需文件均在：`fabric.mod.json`、`tellus.mixins.json`、`tellus.client.mixins.json`、`assets/tellusplus/icon.png`。

**`fabric.mod.json` 依赖声明（从 jar 内实际读出）**

```json
{ "fabricloader": ">=0.16.10", "minecraft": "~1.20.1", "java": ">=17", "fabric-api": "*" }
```

---

## 3. 我改了哪些 API（1.21.9 → 1.20.1）

共 **47 个源文件与本模块 mc1219 版本不同**（154 main + 34 client）。核心差异如下，每条都经 javap 对真实 1.20.1 字节码核实。

### 3.1 网络栈 —— 整层重写（**跨了 1.20.5 的重构**）

`CustomPacketPayload` / `StreamCodec` / `ByteBufCodecs` / `PayloadTypeRegistry` **在 1.20.1 全部不存在**（1.20.5+ 才有）。1.20.1 的 Fabric API 走的是旧的 `FabricPacket` + `PacketType`。

| 1.21.9 | 1.20.1 |
|---|---|
| `implements CustomPacketPayload` | `implements FabricPacket` |
| `StreamCodec.composite(...)` | `void write(FriendlyByteBuf)` + 静态 `fromBuf` |
| `CustomPacketPayload.Type<T>` | `PacketType<T>` |
| `PayloadTypeRegistry.playC2S().register(TP, CODEC)` | **无对应物** —— `PacketType.create()` 自带注册 |
| `ServerPlayNetworking.Context` | handler 签名 `(T payload, ServerPlayer player, PacketSender sender)` |
| `ClientPlayNetworking.Context` | handler 签名 `(T payload, LocalPlayer player, PacketSender sender)` |

**8 个 payload 类全部重写**，字节序保持与 1.21.9 编码器一致（我逐个对照了字段顺序）。

### 3.2 世界生成 / 区块

| 1.21.9 | 1.20.1 | 实证 |
|---|---|---|
| `net.minecraft.world.level.chunk.status.ChunkStatus` | `net.minecraft.world.level.chunk.ChunkStatus`（且是 class，非 enum） | 包不存在 |
| `ChunkGenerator.codec()` → `MapCodec` | → `Codec` | javap |
| `ChunkGenerator.fillFromNoise(Blender,...)` | 多了首参 `Executor` | javap |
| `ChunkGenerator.applyCarvers(...,ChunkAccess)` | 多了尾参 `GenerationStep.Carving` | javap |
| `ChunkGenerator.createStructures(...,ResourceKey<Level>)` | **只有 5 参** | javap + `ChunkStatus` 调用点 |
| `PalettedContainerFactory` | 不存在 → `new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, state, Strategy.SECTION_STATES)` | javap |
| `ChunkAccess.getMinY()/getMaxY()` | `getMinBuildHeight()/getMaxBuildHeight()` | javap |
| `SerializableChunkData` | 不存在（1.20.2+） | 类不存在 |
| `HolderLookup.Provider` | `RegistryAccess` | 嵌套类不存在 |
| `Registry.getValueOrThrow(k)` | `getOrThrow(k)` | javap |
| `RegistryAccess.lookupOrThrow(k)` | `registryOrThrow(k)`（`Registry` 在 1.20.1 **不**实现 `HolderGetter`） | javap |
| `Registry.getOrThrow(k)` 返回 Holder | 返回值，Holder 要走 `getHolderOrThrow(k)` | javap |
| `BiomeGenerationSettings.getCarvers()` | `getCarvers(GenerationStep.Carving)` | javap |
| `Biome.getPrecipitationAt(BlockPos,int)` | `(BlockPos)` | javap |

### 3.3 客户端 UI（**结构性差异，不是改名**）

| 1.21.9 | 1.20.1 |
|---|---|
| `MouseButtonEvent` / `KeyEvent` | **不存在** → `mouseClicked(double,double,int)` / `keyPressed(int,int,int)` |
| `AbstractWidget.onClick(MouseButtonEvent,boolean)` | `onClick(double,double)` |
| `AbstractWidget.onDrag(MouseButtonEvent,double,double)` | `onDrag(double,double,double,double)` |
| `AbstractWidget.onRelease(MouseButtonEvent)` | `onRelease(double,double)` |
| `mouseScrolled` 4 double | **3 double**（无水平分量） |
| `AbstractWidget.setHeight(int)` | **不存在**（只有 `setWidth`） |
| `PoseStack.pushMatrix()/popMatrix()` | `pushPose()/popPose()` |
| `PoseStack.translate(x,y)` / `scale(s,s)` | 必须 3 参 |
| `GuiGraphics.pose()` → `Matrix3x2f` | `PoseStack.Pose` 只暴露 `Matrix4f` |
| `RenderPipelines` / `TextureSetup` / `GuiElementRenderState` / `GuiRenderState` | **全部不存在**（1.21.6+ GUI 渲染重构） |
| `NativeImage.setPixelABGR` | `setPixelRGBA` |
| `new DynamicTexture(Supplier, NativeImage)` | 只有 `DynamicTexture(NativeImage)` |
| `Window.getGuiScale()` 赋 int | 返回 `double`，需强转 |
| `InputConstants.isKeyDown(Window,int)` | `(long,int)` |
| `AbstractSelectionList.setX/setY/setWidth/setHeight` | **全不存在** → `updateSize(w,h,y,itemHeight)` + `setLeftPos(x)` |
| `AbstractSelectionList.Entry.render` 短形式 | **10 参**形式 |
| `getContentX/Y/Width/Height()` | `getRowLeft()/getRowTop(i)/getRowWidth()` |
| `ContainerObjectSelectionList` 5 参 | **6 参** |
| `LevelLoadingScreen.Reason` | **不存在** |
| `RegistrationInfo` / `KnownPack` | **不存在**（1.21+）→ 用 `Lifecycle`，`lifecycle(T value)` |
| `WorldDimensions` 用 `Map` 装 stems | 用 `Registry<LevelStem>` |
| `WorldCreationContext` 7 参 | **6 参**（`initialWorldCreationOptions()` 不存在） |
| `LevelLoadingScreen` 的 `Reason` 门控 | 删除（详见 3.4） |

### 3.4 行为降级 / 有语义差异的改动（**逐条列明**）

1. **`retargetTrialChamberStarts` → 空实现。**
   1.20.1 的 `BuiltinStructures` 共 33 项，**没有 `TRIAL_CHAMBERS`**（试炼密室是 1.21 特性）。无等价物可替代，故不做。代码内已注释说明。

2. **`SerializableChunkDataSparseMixin`（存档瘦身）→ 不做。**
   1.20.1 的 `ChunkSerializer.write` 直接拼 NBT，**没有 `List.add` 可拦**；做等效需重写整个 section 写入循环。判定风险远大于收益。`SparseSections.skipOnSave` 保留但无调用者。

3. **`LevelLoadingScreenMixin` 的 `Reason` 门控 → 删除。**
   1.20.1 的 `LevelLoadingScreen` 没有 `reason` 字段。原门控「只在通用加载屏显示署名」无法表达，改用 `tellus$isLoadingTellusWorld()`（本来也是原条件的另一半）。
   **后果**：署名文字在某些非通用转场的加载屏上也会出现，1.21.9 会抑制。**纯外观差异，一行注释已记录。**

4. **`EarthSlider.onClick` 的右键分支 → 移到屏幕级处理。**
   1.20.1 的 `onClick(double,double)` 不带 button，无法表达「button == 1 时打开输入框」。但该功能**没有丢失**：`EarthCustomizeScreen.mouseClicked` 已在 `button == 1 && scaleSlider.isMouseOver(...)` 时自行调用 `tellus$openInput()`。

5. **`AbstractButton.setHeight` 调用 → 删除（冗余）。**
   每个被删的 `setHeight` 都对应一个构造时已用 `.bounds(0,0,w,20)` 建好的按钮，且容器 `super(0,0,0,20,...)` —— 值本来就一致，删掉不改变几何。

6. **`TerrainPreview` 渲染管线 → 立即模式重写（不是降级为不渲染）。**
   见 3.5。

### 3.5 `TerrainPreview` 的重写（本模块最高风险改动）

1.21.9 走「提交 `GuiElementRenderState` 给 GUI 渲染器稍后批处理」的保留式管线。**1.20.1 完全没有这套**（无 `GuiRenderState`/`GuiElementRenderState`/`TextureSetup`/`RenderPipelines`）。

**做法**：改为立即模式直写 —— `graphics.bufferSource().getBuffer(RenderType.gui())`，在 `enableScissor`/`disableScissor` 内逐顶点输出。

**几何数学逐行未动**：`buildModelView`、`buildProjection`、`emitVertex` 的投影变换、`computeQuadShade`、`applyShade`、`sortQuads` 全部原样。

**⚠️ 已知差异（必须实机验证）**：
> 剪裁与 z 序在 1.21.9 由 GUI 渲染器统一处理，1.20.1 没有。我用 `enableScissor`/`disableScissor` 手动包住绘制，是最接近的等价物，但**不是**逐位一致：其他控件在本次调用之后绘制时，不再由 GUI 渲染器统一排序。

---

## 4. mixin 核实（任务书点名的硬性要求）

### 4.1 mixin 注入点核实方式（javap 输出摘要）

核实对象：`minecraft-merged-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar`（本机 loom 缓存）

#### A. 我修正的 **5 处描述符错配**（原来的写法在 1.20.1 上会失效或崩）

| Mixin | mc1219 写法 | 1.20.1 实际（javap） |
|---|---|---|
| `ChunkAccessSparseSectionsMixin` | `setBlockState(BlockPos;BlockState;)BlockState` | **2 参形式不存在**；只有 `setBlockState(BlockPos, BlockState, Z)Z` |
| `LevelChunkSparseSectionsMixin` | `setBlockState(...;BlockState;I)BlockState` | 第三参是 **`boolean`**（1.21.9 是 `int`） |
| `ProtoChunkSparseSectionsMixin` | 同上 | 同上 |
| `LevelChunkSparseClientMixin` | `replaceWithPacketData(FriendlyByteBuf;Map;Consumer)` | 第二参是 **`CompoundTag`** |
| `ChunkPacketCapMixin` | `<init>(RegistryFriendlyByteBuf;II)` | `RegistryFriendlyByteBuf` **不存在**；实际 `<init>(FriendlyByteBuf;II)` |

`ChunkPacketCapMixin` 的常量本身仍在：javap 显示该构造器偏移 42 `ldc int 2097152`，偏移 51 紧接着字符串 `"Chunk Packet trying to allocate too much memory on read."`。

#### B. `SerializableChunkData` 的等价重实现（**崩溃面真实存在**）

`SerializableChunkData` 在 1.20.1 不存在，但**崩溃面存在，只是换了位置**。我 javap 了 1.20.1 的 `ChunkSerializer` / `ChunkAccess`：

```
ChunkSerializer.read 偏移 1090..1148：
  1093: iload 25
  1097: invokevirtual ListTag.size:()I        ← 循环上界来自【文件里的】列表长度
  1131: invokevirtual ListTag.getShort:(I)S
  1136: invokevirtual ChunkAccess.addPackedPostProcess:(SI)V   ← 注入点

ChunkAccess 构造器 偏移 77..85：
  77: invokeinterface LevelHeightAccessor.getSectionsCount:()I  ← 用【当前维度】的段数
  82: anewarray ShortList
  85: putfield postProcessing                                   ← 分配数组

ChunkAccess.getOrCreateOffsetList 偏移 2：
  2: aaload                                                     ← 裸索引，无边界检查
```

→ 结论：**「存档来自更高维度、用更矮维度读回」同样 AIOOBE**。我据此在 `ChunkSerializer.read` 上重新实现守卫（新文件 `ChunkSerializerPostProcessGuardMixin`），而不是丢弃这个修复。

#### C. 核实通过、描述符一字不差的目标

`Biome.shouldFreeze(LevelReader,BlockPos,Z)Z` · `DimensionType.method_28522`（owner + `(RecordCodecBuilder$Instance)App` 一致）· `DimensionType.MAX_Y:I` · `BlockPos.asLong(III)J` / `getX(J)I` / `getY(J)I` / `getZ(J)I` · `CarvingMask(II)V` · `LevelChunkSection.setBlockState(IIILBlockState;)` 与 5 参形式 / `fillBiomesFromNoise` / `readBiomes` · `BulkSectionAccess.getSection(BlockPos)` · `ClientboundLevelChunkPacketData(FriendlyByteBuf,II)V` · `OceanMonumentPieces$MonumentBuilding.postProcess(...)` · `OceanMonumentStructure.regeneratePiecesAfterLoad` · `DataLayerStorageMap.copyDataLayer(J)` · `LayerLightSectionStorage.getDataLayer(JZ)` · `ChunkAccess.getHighestFilledSectionIndex()` · `LayerLightSectionStorage` 的 `DataLayerStorageMap.copyDataLayer(J)` · `MinecraftServer.runServer/tickServer/stopServer` · `PresetEditor.<clinit>` 的 `Map.of(LLLL)`（**恰好 1 处**，所以 `ordinal = 0` 安全）

### 4.2 `require = 0` 清理

mc1219 有 **6 处**实际使用。我按「目标是 MC 自身类 还是 第三方 Voxy」分类：

**去掉（改为必需）—— 5 处**，javap 已证实目标存在：
- `LightMissingLayerGuardMixin`: `DataLayerStorageMap.copyDataLayer(J)`
- `LightMissingLayerGuard2Mixin`: `LayerLightSectionStorage.getDataLayer(JZ)`
- `VoxyServerTickMixin`: `MinecraftServer.runServer` / `tickServer` / `stopServer` —— **名字有 Voxy，但 `@Mixin(MinecraftServer.class)` 注入的是原版类**，所以这里的 `require = 0` 是真正的违规

**保留 —— 4 处**，全部是 `me.cortex.voxy.*` 第三方目标（可选软依赖，本机无 Voxy jar，无法核实其方法存在性）。

**mc1201 新代码里我没有新增任何 `require = 0`。**

### 4.3 删 / 增的文件（完整清单）

| 操作 | 文件 | 原因 |
|---|---|---|
| 删 | `mixin/SerializableChunkDataPostProcessGuardMixin.java` | 类不存在 → 由 `ChunkSerializerPostProcessGuardMixin` 等价重实现 |
| 删 | `mixin/SerializableChunkDataSparseMixin.java` | 1.20.1 无 `List.add` 可拦 → 判定不做 |
| 删 | `mixin/client/GuiGraphicsAccessor.java` | `GuiRenderState` 不存在 → **同步从 `tellus.client.mixins.json` 移除声明** |
| 增 | `mixin/ChunkSerializerPostProcessGuardMixin.java` | 上述等价重实现 |

---

## 5. 验收核对（对 Lead 列的 5 条）

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 两个源码集都 SUCCESS | ✅ | `clean :mc1201:build -x test` → BUILD SUCCESSFUL，0 错误 |
| 2 | jar 内有真实模组 class | ✅ | **659** 个 `com/yucareux/*`（此前工具链产物是 0 个） |
| 3 | mixin 双向零差异 | ✅ | 声明 27 = jar 内 27；缺失 0、未声明 0 |
| 4 | `Cannot remap` 警告 0 条 | ✅ | 实测 0 |
| 5 | `fabric.mod.json` 的 minecraft 是 `~1.20.1` | ✅ | 从 jar 内读出 |

---

## 6. 未验证项（**如实列出**）

1. **功能正确性 —— 完全未验证。** 我没有启动过游戏（装机不在我的任务范围内）。**「编译通过」不蕴含「能运行」。** 特别是：
2. **`TerrainPreview` 的视觉效果未验证。** 从保留式管线改为立即模式，剪裁与 z 序的处理方式不同（见 3.5）。**可能有可见差异。**
3. **mixin 的运行时注入成功性未验证。** javap 只能证明「目标方法存在且描述符匹配」，**不能**证明 `@Redirect`/`@ModifyConstant` 的注入点在运行期唯一命中。`defaultRequire: 1` 保证失败会**显式报错**而不是静默跳过，但成功与否需实机启动才能确认。
4. **`require = 0` 保留的 4 处（Voxy）的注入点存在性未核实。** 本机没有 Voxy jar。**（安全性已论证：目标类完全不存在时 mixin 框架会跳过整个 mixin，所以没装 Voxy 的用户不会崩 —— 这里「未核实」指的是注入点存在性，不是安全性。）**
5. **`DynamicTexture` 的纹理调试名丢失。** 1.21.9 的 `(Supplier<String>, NativeImage)` 在 1.20.1 无对应参数，supplier 被丢弃 —— 仅影响调试可读性，不影响渲染。
6. **`EarthChunkGenerator` 的语义正确性只做了静态审计。** 子代理做了「编译产物 vs 真实 1.20.1 `ChunkGenerator`」的全量方法比对（15 个预期 override 全部匹配），但这是**静态**比对。

---

## 11. 实机自查轮（Lead 要求，v2 新增）

> 触发原因：26.2 暴露了「编译全绿但游戏崩」的数据文件问题。Lead 要求 1.20.1 也做一轮。

### 11.1 ★ 检查项 1：data JSON schema —— **发现崩溃级问题，已修**

**问题**：`data/tellus/dimension_type/earth.json` 与 `earth_dynamic.json` 是 **1.21.9 格式原样复制**的，有 **2 处**在 1.20.1 上不成立。

**核实方式（权威 ground truth，不是推理）**：
从 **1.20.1 原版 jar** 提取 `data/minecraft/dimension_type/overworld.json`，并与 **1.21.9 原版 jar** 的同一文件逐一对照：

| 键 | 1.20.1 原版 | 1.21.9 原版 | 我的文件（修复前） |
|---|---|---|---|
| `cloud_height` | **不存在** | `192` | `192` ❌ |
| `monster_spawn_light_level` | `{type, value:{min_inclusive, max_inclusive}}` | `{type, min_inclusive, max_inclusive}`（扁平） | 扁平 ❌ |

**第二处差异的根因（javap 实证 —— 这才是关键）**：

不是 Minecraft 改的，是 **DFU 库版本**改的：

```
1.20.1 → datafixerupper 6.0.8   （gradle dependencies 实测）
1.21.9 → datafixerupper 10.x

6.0.8  Codec.dispatch(Function, Function<A, Codec<? extends E>>)     → 包一层 "value"
10.x   Codec.dispatch(Function, Function<A, MapCodec<? extends E>>)   → 内联（扁平）
```

这正好解释了为什么两个原版文件形状不同。**我原来写的是 10.x 的形状**，在 6.0.8 下解析不出 `value`。

**`cloud_height` 的确证**：javap `-v` 整个 1.20.1 `DimensionType` 类，`cloud_height` 字符串出现 **0 次** —— 1.20.1 的 codec **从不读它**（1.21 才成为组件）。

**严重性判断**：`cloud_height` 是未知键（RecordCodecBuilder 默认忽略），**单独不会崩**；但 `monster_spawn_light_level` 形状错误会让 `IntProvider` 解析失败 → `DimensionType` 解析失败 → **registry loading 失败 → 世界加载崩溃**，与 26.2 报告的现象同类。

**修复**：移除 `cloud_height`，把 `monster_spawn_light_level` 改为嵌套 `value` 形状。
**修复后逐键比对**（对 1.20.1 原版模板）：extra keys **none**、missing keys **none**、`light_level` 形状 = `type, value`。✅

**同时扫描了其余全部 data JSON**：

| 文件类别 | 核查结论 |
|---|---|
| `tellus/worldgen/biome/*.json`（5 个） | 键集合与 1.20.1 原版 `plains.json` **完全一致**（8 键）；`carvers` 存在；`features` 数组长度 = **11**（与 1.20.1 原版一致，对应 11 个 generation step）✅ |
| `minecraft/worldgen/structure/igloo.json` | 键集合与 1.20.1 原版 igloo **完全一致**（4 键）✅ |
| `minecraft/worldgen/structure/ancient_city.json` | 与 1.20.1 原版**完全一致** ✅ |
| `minecraft/tags/...`、`tellus/worldgen/world_preset/earth.json` | 无版本敏感键 ✅ |

**注意**：这 10 个共享 data 文件位于**仓库根 `src/main/resources`**（我只读不改），所以这处修复只落在 mc1201 自己的 2 个文件上。**如果 mc1211/mc12111/mc262 也要 1.20.x/1.21.x 的兼容性，根目录那份 biome/structure schema 对各自版本是否成立，不在我的写作用域内。**

### 11.2 检查项 2：`compatibilityLevel` —— ✅ 已确认

**从最终 jar 内读出**（不是从源码）：

```
tellus.mixins.json         compatibilityLevel = JAVA_17
tellus.client.mixins.json  compatibilityLevel = JAVA_17
```

### 11.3 检查项 3：`fabric.mod.json` 的 `minecraft` —— ✅ 已确认

**从最终 jar 内 `fabric.mod.json` 读出**：

```
minecraft = ~1.20.1
```

（这条我在第一轮就核对过，本轮从**重建后的新 jar** 再确认一次。）

### 11.4 检查项 4：`Cannot remap` —— ✅ 0 条

```
clean :mc1201:compileJava :mc1201:compileClientJava → BUILD SUCCESSFUL
Cannot remap 警告                                       → 0
```

### 11.5 检查项 5：静默失效系统性排查

**方法**：枚举 mc1201 全部 **29 处 `super.xxx(...)` 调用**，对每一个用 javap 确认 1.20.1 基类**确实声明了该签名**：

| super 调用 | 1.20.1 基类声明 | 结论 |
|---|---|---|
| `super.render(GuiGraphics,int,int,float)` ×6 | `AbstractWidget.render(...)` / `Screen.render(...)` | ✅ |
| `super.mouseClicked(double,double,int)` ×5 | 同签名 | ✅ |
| `super.tick()` ×4 | `Screen.tick()` | ✅ |
| `super.keyPressed(int,int,int)` ×2 | `Screen` / `GuiEventListener` 同签名 | ✅ |
| `super.renderWidget(...)` ×2 | `AbstractWidget.renderWidget` 是 **abstract** | ✅（调用点在自身子类里，编译通过即证明解析到了实现） |
| `super.mouseReleased(double,double,int)` | 同签名 | ✅ |
| `super.mouseScrolled(double,double,double)` | `AbstractWidget` 同签名（3 参） | ✅ |
| `super.isMouseOver(double,double)` | `AbstractWidget` 同签名 | ✅ |
| `super.onClick(double,double)` | `AbstractWidget.onClick(double,double)` | ✅ |
| `super.onDrag(double,double,double,double)` | `AbstractWidget.onDrag(4 参)` | ✅ |
| `super.createStructures(RegistryAccess,ChunkGeneratorStructureState,StructureManager,ChunkAccess,StructureTemplateManager)` | **5 参**，与调用一致 | ✅（这正是第一轮修掉的死代码 bug） |
| `super.applyBiomeDecoration(WorldGenLevel,ChunkAccess,StructureManager)` | 同签名 | ✅ |
| `super.createReferences(WorldGenLevel,StructureManager,ChunkAccess)` | 同签名 | ✅ |
| `super.removed()` | `Screen.removed()` | ✅ |
| `super.execute(command)` | `ThreadPoolExecutor.execute(Runnable)` | ✅（非 MC 类） |

**14/14 全部匹配，未发现新的「参数个数不同但签名合法」的死代码。**

### 11.6 检查项 6：scissor 配对 —— **发现 3 处泄漏风险，已加固**

Lead 提示「漏掉一对 scissor 会导致后续所有 GUI 绘制被裁掉，是视觉灾难级 bug」。核查结果：

| 位置 | 修复前 | 处置 |
|---|---|---|
| `TerrainPreview.render` | 已有 `try { ... } finally { disableScissor(); }` | ✅ 本来就正确 |
| `SlippyMapWidget.renderWidget` | `enable`(83) → `disable`(108)，**无 try/finally**。当时无提前 return，但渲染 tile / map component 抛异常会**带着 scissor 逃逸** | 🔧 **已包 try/finally** |
| `PreloadOverlayMapComponent` | `enable`(85) → `redrawTiles` → `disable`(91)，无 try/finally | 🔧 **已包 try/finally** |
| `PlaceSearchWidget` | `enable`(344) → `drawString` → `disable`(346)，无 try/finally | 🔧 **已包 try/finally** |

**修复后重新计数：4 处 `enableScissor` / 4 处 `disableScissor`，全部在同一 try/finally 内配对。**

### 11.7 关于那 4 处 `require = 0`（Voxy）—— 保持原判

本轮重新确认：全部 4 处目标均为 `me.cortex.voxy.*`（第三方可选软依赖）。**未改动。**
（再次澄清 Lead 的措辞要求：这里「未核实」指的是**注入点存在性**，不是安全性 —— 安全性已论证：目标类不存在时 mixin 框架跳过整个 mixin。）

### 11.8 本轮未能完成的验证（如实说明）

**我尝试用「真实 1.20.1 codec 解析这两个 JSON」来拿到执行级证据，但做不到**：

写了两个临时 JUnit 审计类（`DimensionTypeSchemaAuditTest` / `IntProviderShapeAuditTest`），强制跑测试，结果全部失败：

```
java.lang.IllegalArgumentException: Not bootstrapped (called from registry ...
  at net.minecraft.core.registries.BuiltInRegistries.<clinit>
```

**原因**：`DimensionType` / `IntProvider` 的静态初始化依赖 Minecraft 的 registry bootstrap，纯 JUnit 没有 Fabric launcher，无法执行 codec。

**我改用更强的证据**：**1.20.1 原版 jar 里的同名数据文件本身**，加上 **javap 反汇编的 codec 字段名**与 **DFU 版本差异**。这比「解析一个我构造的样本」更权威 —— 原版文件就是 1.20.1 必须能解析的东西。

**这两个临时测试类与 fixture 我已删除**（它们无法运行，留着会污染测试集）。**原有测试源码未被触碰**：仓库根 `src/test/java` 的 2 个测试与 `src/test/resources/fixtures` 原样保留。

**因此本节的结论强度是**：schema 差异与修法有**权威 ground truth 佐证**，但「修完的 JSON 在真实 1.20.1 客户端上确实加载成功」**仍未在运行环境中观测过**。

---

## 7. 已知失败项（**不隐瞒**）

### 7.1 `:mc1201:test` 有 2 个失败 —— **不是本次移植引入的**

```
EarthGeneratorSettingsCodecTest > roundTripsCurrentSettingsPayload() FAILED
EarthGeneratorSettingsCodecTest > loadsLegacy12111SettingsFixture() FAILED
Caused by: NullPointerException:
  FabricLoaderImpl.getEnvironmentType() ... FabricLauncherBase.getLauncher() is null
  at EarthGeneratorSettings.<clinit>(EarthGeneratorSettings.java:115)
```

**根因**：`EarthGeneratorSettings` 的静态初始化调用了 `FabricLoader.getInstance().getEnvironmentType()`，而纯 JUnit 环境下没有 Fabric launcher。这是**代码与测试环境的固有不兼容**，与 MC 版本无关。

**证据（不是我造成的）**：mc1219 自己留下的历史测试结果 XML 里是**同样的两条失败**：

```
mc1219/build/test-results/test/TEST-...EarthGeneratorSettingsCodecTest.xml
  <testsuite tests="2" failures="2" timestamp="2026-10-11T08:52:51Z">
  <failure>Caused by: NullPointerException: ... FabricLauncherBase.getLauncher() is null</failure>
```

我**没有**去掩盖它（没有加 `-x test` 到构建配置里、没有跳过测试），只在验收时用 `-x test` 单独跑 `build` 以区分「编译问题」与「既有测试问题」。

**因此 `:mc1201:build`（含 test）会失败，`:mc1201:build -x test` 成功。** 这条差异请 Lead 知悉。

---

## 8. 我改错了又改回来的地方（记录以免重犯）

1. **`getMaxY()` → `getMaxBuildHeight()` 的差一错误。** 机械替换时漏了语义差异。javap 实证：

   ```
   1.21.9 getMaxY()           : getMinY() + getHeight() - 1   ← 含端点（末尾有 isub）
   1.20.1 getMaxBuildHeight() : getMinBuildHeight() + getHeight()  ← 不含端点（无 isub）
   ```
   后果：`spawnOriginalMobs` 取到了建筑限高**上方一格**的群系（非法坐标）。已修为 `- 1`。（同文件另 3 处本来就带了 `-1`，唯独这处漏了。）

2. **`createStructures` 多留了一个参数 → 静默变死代码。**
   我把 1.21.9 的 6 参形式带进了 mc1201，但 1.20.1 只有 5 参。多一个参数的重载是合法 Java，**编译无警告**，但基类永不调用 —— 于是 `super.createStructures(...)` 与全部 8 个结构过滤步骤**全部成为死代码**。javap `ChunkStatus` 确认调用点是 5 参。已修为 5 参。

   > **这类缺陷比编译错误危险得多**：它不需要 `require = 0` 就能静默失效。

3. **PowerShell `Set-Content -Encoding UTF8` 写入 BOM**，javac 报 `非法字符: '\ufeff'`。后续所有整文件写入改用 `[System.IO.File]::WriteAllText($p, $t, UTF8Encoding($false))`。

4. **我的批量正则误伤了 3 处**（把 `for (Button button : ...)` 里的循环变量改成了 `mouseX/mouseY`、把 `DynamicTexture(() -> fmt, img)` 改坏、漏掉 `translate(int,int)` 这种 int 实参形式）。均已定位并修正。

---

## 9. 调度失误（我的责任）

我在同一批 client 文件上**同时**派了子代理又自己动手改，造成并发写冲突。子代理主动发现（用 mtime + size 双指标）并停下来询问，没有互相覆盖 —— 但这是我的调度错误。

**教训**：派出去的文件在子代理结束前不应再由派发者编辑；应由 `write_scopes` 显式隔离。

---

## 10. 遵守的硬性约束

| 约束 | 状态 |
|---|---|
| 不用 `require = 0`（新增代码） | ✅ 0 处 |
| 不装机（不写 `mods/`） | ✅ 未写任何 mods 目录 |
| 不伪造成功 | ✅ 本报告区分「编译通过」与「功能正确」，并列出 2 个失败的测试 |
| 不改 `mc1219/` | ✅ 未改（mtime 2026-09-25） |
| 不改根 `src/` | ✅ 只读 |
| 不动 `settings.gradle` | ✅ 未改 |
| 每个版本独立构建验证 | ✅ 全程只用 `:mc1201:*` |
| jar 放 `mc1201/build/libs/` | ✅ |
