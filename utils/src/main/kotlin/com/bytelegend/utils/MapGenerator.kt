/*
 * Copyright 2021 ByteLegend Technologies and the original author or authors.
 * 
 * Licensed under the GNU Affero General Public License v3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      https://github.com/ByteLegend/ByteLegend/blob/master/LICENSE
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytelegend.utils

import com.bytelegend.app.shared.BLOCKER
import com.bytelegend.app.shared.ConstantPoolEntry
import com.bytelegend.app.shared.GridCoordinate
import com.bytelegend.app.shared.GridSize
import com.bytelegend.app.shared.NON_BLOCKER
import com.bytelegend.app.shared.PixelBlock
import com.bytelegend.app.shared.PixelSize
import com.bytelegend.app.shared.RGBA
import com.bytelegend.app.shared.RawAnimationLayer
import com.bytelegend.app.shared.RawGameMap
import com.bytelegend.app.shared.RawGameMapTile
import com.bytelegend.app.shared.RawGameMapTileLayer
import com.bytelegend.app.shared.RawStaticImageLayer
import com.bytelegend.app.shared.RawTileAnimationFrame
import com.bytelegend.app.shared.map
import com.bytelegend.app.shared.objects.GameMapDynamicSprite
import com.bytelegend.github.utils.generated.TiledMap
import com.bytelegend.github.utils.generated.TiledTileset
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/**
 * [mapId, tiledMapJson, gameDataDir, outputMapDir]
 */
@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
fun main(args: Array<String>) {
    val mapId = args[0]
    // ./resources/raw/maps/{mapId}/{mapId}.json
    val tiledMapJson = File(args[1])
    // ./game-data/{mapId}
    val mapDataDir = File(args[2])
    // RRBD/map/{mapId}
    val outputMapDir = File(args[3])

    MapGenerator(
        mapId,
        tiledMapJson,
        File(mapDataDir, "$mapId/missions"),
        outputMapDir
    ).generate()
}

val module = SimpleModule().apply {
    addSerializer(ConstantPoolEntry::class.java, JacksonConstantPoolSerializer)
}
val uglyObjectMapper = ObjectMapper().apply {
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
    registerModule(module)
    registerModule(KotlinModule())
}

val prettyObjectMapper = uglyObjectMapper.writerWithDefaultPrettyPrinter()
private const val TILE_LAYER_TYPE = "tilelayer"
private const val PLAYER_LAYER_NAME = "Player"
private const val BLOCKERS_LAYER_NAME = "Blockers"
private const val DYNAMIC_SPRITES_GROUP_NAME = "DynamicSprites"

class MapGenerator(
    private val mapId: String,
    private val tiledMapJson: File, // input map json generated by Tiled
    private val mapMissionDataDir: File, // game-data/{mapId}/missions
    /**
     * Output directory, usually the map name:
     * ...
     * |_ JavaIsland
     *    |_ map.raw.json
     *    |_ map.json
     *    |_ tileset.png
     *    |_ missions.json
     */
    private val outputDir: File
) {
    init {
        outputDir.mkdirs()
    }

    private val outputRawMapJson = outputDir.resolve("map.raw.json")
    private val outputCompressedMapJson = outputDir.resolve("map.json")
    private val outputMissionsJson = outputDir.resolve("missions.json")
    private val tiledMap: TiledMap = uglyObjectMapper.readValue(tiledMapJson.readText(), TiledMap::class.java)
    private val mapDataReader = MissionDataReader(mapOf(mapId to mapMissionDataDir))
    private val tilesets: List<TilesetAndImage> = tiledMap.tilesets.map {
        val tilesetFile = tiledMapJson.parentFile.resolve(it.source)
        val tileset: TiledTileset = uglyObjectMapper.readValue(tilesetFile.readText(), TiledTileset::class.java)
        val image = tilesetFile.parentFile.resolve(tileset.image)
        TilesetAndImage(tileset, image, it.firstgid.toInt())
    }

    private val imageReader: ImageReader = ImageReader()
    private val tilesetWriter: TilesetImageWriter = TilesetImageWriter(outputDir.resolve("tileset.png"))

    private val srcTiles: LinkedHashMap<GridCoordinate, RawTileLayers> = LinkedHashMap()
    private val blockers: MutableMap<GridCoordinate, Int> = mutableMapOf()

    val destTileSize = PixelSize(32, 32)

    /**
     * How source tiles are mapped to dest tile
     */
    private val srcImageBlocksToDestBlockMapping: LinkedHashMap<List<ImageBlock>, Int> = LinkedHashMap()

    private var used: Boolean = false

    private val visibleFlattenedLayers: List<TiledMap.Layer> = tiledMap.layers.apply {
        // Check dumplicate layer id
        groupBy { it.id }.forEach { (layerId: Long, layers: List<TiledMap.Layer>) ->
            if (layers.size > 1) {
                throw IllegalStateException("Multiple layers with same id: $layerId ${layers.joinToString(",") { it.name }}")
            }
        }
    }.flatMap { layer ->
        when {
            layer.name == BLOCKERS_LAYER_NAME -> emptyList()
            layer.name == DYNAMIC_SPRITES_GROUP_NAME -> emptyList()
            !layer.visible -> emptyList()
            layer.type == TILE_LAYER_TYPE -> listOf(layer)
            layer.type == "group" -> layer.layers.filter { it.visible }.map {
                TiledMap.Layer(
                    it.data,
                    it.height,
                    it.id,
                    it.name,
                    it.opacity,
                    it.type,
                    it.visible,
                    it.width,
                    it.x,
                    it.y,
                    emptyList(),
                    layer.draworder,
                    emptyList()
                )
            }
            else -> listOf(layer)
        }
    }

    // Player layer is 0, layers above are positive, layers below are negative
    private val playerLayerIndex: Int = visibleFlattenedLayers.indexOfFirst { it.name == PLAYER_LAYER_NAME }.apply {
        require(this != -1) { "You must have a layer named `Player`" }
    }
    private val rawLayerIdToIndexMap = visibleFlattenedLayers.mapIndexed { i, layer ->
        layer.id.toInt() to i - playerLayerIndex
    }.toMap()
    private val tiledObjectReader = TiledObjectReader(mapId, tiledMap, mapDataReader, rawLayerIdToIndexMap)
    private val dynamicSpriteReader = DynamicSpriteReader()

    fun generate() {
        require(!used) { "An instance can only be used once!" }
        used = true

        populateBlockersMap()

        for (y in 0 until tiledMap.height) {
            for (x in 0 until tiledMap.width) {
                val tileLayersAtCoordinate = visibleFlattenedLayers
                    .filter { it.type == TILE_LAYER_TYPE && it.name != PLAYER_LAYER_NAME }
                    .flatMap { layer ->
                        val i = (y * tiledMap.width + x).toInt()
                        val tileIndex = layer.data[i].toInt()
                        if (tileIndex == 0) emptyList()
                        else listOf(resolveTile(tileIndex, rawLayerIdToIndexMap.getValue(layer.id.toInt())))
                    }.removeRedundantLayers()
                srcTiles[GridCoordinate(x.toInt(), y.toInt())] = RawTileLayers(tileLayersAtCoordinate)
            }
        }

        squashAndDeduplicateSrcTiles()
        dynamicSpriteReader.readDynamicSpriteAndPopulateTileset()

        // Now we know how many dest tiles, and how large the dest tileset is, let's generate!
        initDestTilesetImage()
        tilesetWriter.generateDestTilesetImage()

        generateDestGameMap(outputRawMapJson, outputCompressedMapJson)
        generateMissionsJson()
    }

    private fun generateMissionsJson() {
        outputMissionsJson.writeText(uglyObjectMapper.writeValueAsString(tiledObjectReader.readAndMergeMissionSpecs()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateDestGameMap(
        outputRawMapJson: File,
        outputCompressedMapJson: File
    ) {
        val destTiles: MutableList<MutableList<RawGameMapTile?>> = MutableList(tiledMap.height.toInt()) { MutableList(tiledMap.width.toInt()) { null } }

        for (y in 0 until tiledMap.height.toInt()) {
            for (x in 0 until tiledMap.width.toInt()) {
                destTiles[y][x] = calculateFinalTile(x, y)
            }
        }

        val rawMap = RawGameMap(
            mapId,
            GridSize(tiledMap.width.toInt(), tiledMap.height.toInt()),
            tiledMap.getTileSize(),
            destTiles as List<List<RawGameMapTile>>,
            tiledObjectReader.readRawObjects() + dynamicSpriteReader.getDynamicSprites()
        )
        val compressedMap = rawMap.compress()
        require(compressedMap.decompress().compress() == compressedMap)

        if ("dev" == System.getProperty("environment")) {
            prettyObjectMapper.writeValue(outputRawMapJson, rawMap)
        }

        outputCompressedMapJson.writeText(uglyObjectMapper.writeValueAsString(compressedMap))
    }

    private fun initDestTilesetImage() {
        val totalBlocks = srcImageBlocksToDestBlockMapping.size + 1.0
        val destTilesetGridWidth = totalBlocks.pow(0.5).toInt()
        val destTilesetGridHeight = ceil(totalBlocks / destTilesetGridWidth).toInt()

        require(destTilesetGridHeight * destTilesetGridWidth >= totalBlocks)

        tilesetWriter.init(GridSize(destTilesetGridWidth, destTilesetGridHeight))
    }

    private fun putIntoSrcImageBlocksToDestBlockMapping(blocks: List<ImageBlock>) {
        srcImageBlocksToDestBlockMapping.computeIfAbsent(blocks) {
            srcImageBlocksToDestBlockMapping.size + 1
        }
    }

    /**
     * - Deduplicate: remove duplicate tiles
     */
    private fun squashAndDeduplicateSrcTiles() {
        srcTiles.values.forEach { layers ->
            layers.squashedLayers.forEach { layer ->
                layer.destTiles.forEach {
                    putIntoSrcImageBlocksToDestBlockMapping(it.blocks)
                }
            }
        }
    }

    private fun populateBlockersMap() {
        val blockerLayer = tiledMap.layers.firstOrNull { it.name == BLOCKERS_LAYER_NAME } ?: return
        blockerLayer.data.withIndex().filter { it.value != 0L }.forEach {
            val x = it.index % tiledMap.width
            val y = it.index / tiledMap.width
            blockers[GridCoordinate(x.toInt(), y.toInt())] = if (it.value == 0L) NON_BLOCKER else BLOCKER
        }
    }

    /**
     * A dest tile represents the rendered image tile on the final tileset.
     * It may be:
     * 1. Squashed from a series static images.
     * 2. A single frame in animation.
     */
    interface DestTile {
        val blocks: List<ImageBlock>
    }

    interface SquashedLayer {
        /**
         * A squashed layer may produce one or multiple dest tile:
         *
         * 1. A single dest tile squashed from a series of static tiles
         * 2. Multiple dest tiles from animation frames, will not be squashed.
         */
        val destTiles: List<DestTile>
        val layer: Int

        fun toDestLayer(): RawGameMapTileLayer
    }

    /**
     * A compressed layer contains multiple layers of blocks,
     * which will be drawn together to save image data volume.
     */
    inner class MultipleStaticLayersIntoSingleLayer(
        /**
         * The original layers, the first element is at the bottom.
         */
        private val staticImageLayers: List<StaticTileImageLayer>
    ) : SquashedLayer, DestTile {
        override val layer: Int
            get() = staticImageLayers.minOf { it.layer }
        override val destTiles: List<DestTile> = listOf(this)
        override val blocks: List<ImageBlock> = staticImageLayers.map { it.tile }
        override fun toDestLayer(): RawGameMapTileLayer {
            val destTileIndex = srcImageBlocksToDestBlockMapping.getValue(blocks)
            return RawStaticImageLayer(calculateCoordinate(destTileIndex), layer)
        }
    }

    private fun calculateCoordinate(index: Int) = GridCoordinate(
        index % tilesetWriter.gridSize.width,
        index / tilesetWriter.gridSize.width
    )

    private fun calculatePixelBlock(index: Int): PixelBlock {
        val coor = calculateCoordinate(index)
        return PixelBlock(coor.x * destTileSize.width, coor.y * destTileSize.height, destTileSize.width, destTileSize.height)
    }

    @Suppress("TYPE_INFERENCE_ONLY_INPUT_TYPES_WARNING")
    private fun calculateFinalTile(x: Int, y: Int): RawGameMapTile {
        val coordinate = GridCoordinate(x, y)
        val srcTile: RawTileLayers = srcTiles.getValue(coordinate)
        val blocker = blockers.getOrDefault(coordinate, NON_BLOCKER)

        return RawGameMapTile(srcTile.squashedLayers.map { it.toDestLayer() }, blocker)
    }

    inner class TilesetImageWriter(
        private val tilesetImage: File
    ) {
        lateinit var gridSize: GridSize
        lateinit var pixelSize: PixelSize
        fun init(gridSize: GridSize) {
            this.pixelSize = gridSize * destTileSize
            this.gridSize = gridSize
        }

        fun generateDestTilesetImage() {
            val outputImage = BufferedImage(pixelSize.width, pixelSize.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = outputImage.graphics
            srcImageBlocksToDestBlockMapping.forEach { (srcImageBlocks: List<ImageBlock>, destBlockIndex: Int) ->
                val pixelBlock = calculatePixelBlock(destBlockIndex)

                srcImageBlocks.forEach { blockOnLayer ->
                    graphics.drawImage(
                        imageReader.getImage(blockOnLayer.image),
                        pixelBlock.x, pixelBlock.y, pixelBlock.x + pixelBlock.width, pixelBlock.y + pixelBlock.height,
                        blockOnLayer.block.x, blockOnLayer.block.y, blockOnLayer.block.x + blockOnLayer.block.width, blockOnLayer.block.y + blockOnLayer.block.height,
                        null
                    )
                }
            }
            graphics.dispose()
            ImageIO.write(outputImage, "PNG", tilesetImage)
        }
    }

    private fun List<TileLayer>.removeRedundantLayers(): List<TileLayer> {
        if (isEmpty()) {
            return this
        }
        // scan from top to bottom, find first fully opaque layer and remove all layers below
        val topOpaqueLayerIndex = indexOfLast { it.isFullyOpaque(imageReader) }
        return if (topOpaqueLayerIndex == -1) {
            this.filter { !it.isFullyTransparent(imageReader) }
        } else {
            drop(topOpaqueLayerIndex).filter { !it.isFullyTransparent(imageReader) }
        }
    }

    private fun determineTileset(tileIndex: Int): TilesetAndImage {
        for (i in 0 until tilesets.size - 1) {
            if (tileIndex >= tilesets[i].firstgid && tileIndex < tilesets[i + 1].firstgid) {
                return tilesets[i]
            }
        }
        return tilesets.last()
    }

    private fun resolveTile(tileIndex: Int, layerIndex: Int): TileLayer {
        require(tileIndex != 0)
        val tileset: TilesetAndImage = determineTileset(tileIndex)
        val offset = tileIndex - tileset.firstgid
        val animationTile = tileset.tileset.tiles.firstOrNull { it.id.toInt() == offset && it.animation.isNotEmpty() }
        return if (animationTile == null) {
            StaticTileImageLayer(layerIndex, ImageBlock(tileset.image, resolveTileBlock(tileset.tileset, offset)))
        } else {
            AnimationLayer(
                animationTile.animation.map {
                    TileAnimationFrame(
                        ImageBlock(tileset.image, resolveTileBlock(tileset.tileset, it.tileid.toInt())),
                        it.duration.toInt()
                    )
                },
                layerIndex
            )
        }
    }

    private fun resolveTileBlock(tileset: TiledTileset, offset: Int): PixelBlock {
        val tileWidth = tileset.tilewidth.toInt()
        val tileHeight = tileset.tileheight.toInt()
        val imageGridSize = GridSize(
            (tileset.imagewidth / tileWidth).toInt(),
            (tileset.imageheight / tileHeight).toInt()
        )
        val coordinate = GridCoordinate(offset % imageGridSize.width, offset / imageGridSize.width)
        return PixelBlock(
            coordinate.x * tileWidth,
            coordinate.y * tileHeight,
            tileWidth,
            tileHeight
        )
    }

    inner class RawTileLayers(
        val layers: List<TileLayer>
    ) {
        val squashedLayers: List<SquashedLayer>

        init {
            val tmp = mutableListOf<SquashedLayer>()
            tmp.addAll(squash(layers.filter { it.layer < 0 }))
            tmp.addAll(squash(layers.filter { it.layer > 0 }))
            squashedLayers = tmp
        }

        /**
         * Compress continuous static image layers to a single image layer to save data volume
         */
        private fun squash(layers: List<TileLayer>): List<SquashedLayer> {
            val ret = mutableListOf<SquashedLayer>()
            val tmp = mutableListOf<StaticTileImageLayer>()
            layers.forEach { layer ->
                if (layer is AnimationLayer) {
                    if (tmp.isNotEmpty()) {
                        ret.add(MultipleStaticLayersIntoSingleLayer(tmp.toList()))
                        tmp.clear()
                    }

                    ret.add(layer)
                } else {
                    tmp.add(layer as StaticTileImageLayer)
                }
            }
            if (tmp.isNotEmpty()) {
                ret.add(MultipleStaticLayersIntoSingleLayer(tmp.toList()))
            }
            return ret
        }
    }

    inner class AnimationLayer(
        val frames: List<TileAnimationFrame>,
        override val layer: Int
    ) : TileLayer, SquashedLayer {
        override fun isFullyOpaque(imageReader: ImageReader) = frames.all { imageReader.isFullyOpaque(it.imageBlock) }
        override fun isFullyTransparent(imageReader: ImageReader) = frames.all { imageReader.isFullyTransparent(it.imageBlock) }

        override val destTiles: List<DestTile>
            get() = frames

        override fun toDestLayer(): RawGameMapTileLayer = RawAnimationLayer(frames.map { it.toGameFrame() }, layer).apply {
            require(frames.all { it.duration == frames[0].duration }) { "All frames should have same duration!" }
        }
    }

    inner class TileAnimationFrame(
        val imageBlock: ImageBlock,
        val duration: Int
    ) : DestTile {
        override val blocks: List<ImageBlock>
            get() = listOf(imageBlock)

        fun toGameFrame(): RawTileAnimationFrame {
            val targetIndex = srcImageBlocksToDestBlockMapping.getValue(blocks)
            return RawTileAnimationFrame(calculateCoordinate(targetIndex), duration)
        }
    }

    data class DynamicSpriteData(
        val topLeftCorner: GridCoordinate,
        val frames: List<List<List<ImageBlock>>>
    )

    inner class DynamicSpriteReader {
        // key: sprite id
        // value: matrix of sprite frames
        private val dynamicSpriteBlocks: MutableMap<String, DynamicSpriteData> = HashMap()

        /**
         * Read frames of dynamic frames
         *
         * Note: this also populates srcImageBlocksToDestBlockMapping
         */
        fun readDynamicSpriteAndPopulateTileset() {
            val dynamicSpriteLayers = tiledMap.layers.find { it.type == "group" && it.name == "DynamicSprites" }?.layers
                ?: return
            dynamicSpriteLayers.forEach {
                dynamicSpriteBlocks[it.name] = it.getDynamicSpriteData()
            }
        }

        private fun ImageBlock.toTilesetCoordinate(): GridCoordinate {
            return calculateCoordinate(srcImageBlocksToDestBlockMapping.getValue(listOf(this)))
        }

        fun getDynamicSprites(): List<GameMapDynamicSprite> {
            return dynamicSpriteBlocks.entries.map {
                GameMapDynamicSprite(
                    it.key,
                    it.value.frames.map { it.map { it.toTilesetCoordinate() } }
                )
            }
        }

        private fun TiledMap.Layer2.getDynamicSpriteData(): DynamicSpriteData {
            // the sprite can cross multiple tiles.
            val firstTileIndex = data.indexOfFirst { it != 0L }
            val lastTileIndex = data.indexOfLast { it != 0L }
            val firstTileCoordinate = GridCoordinate(firstTileIndex % tiledMap.width.toInt(), firstTileIndex / tiledMap.width.toInt())
            val lastTileCoordinate = GridCoordinate(lastTileIndex % tiledMap.width.toInt(), lastTileIndex / tiledMap.width.toInt())

            val subArrayWidth = abs(lastTileCoordinate.x - firstTileCoordinate.x + 1)
            val subArrayHeight = abs(lastTileCoordinate.y - firstTileCoordinate.y + 1)

            require(subArrayHeight <= 2 && subArrayWidth <= 2) {
                "$name width/height: $subArrayWidth/$subArrayHeight"
            }

            val dataSubArray = mutableListOf<List<List<ImageBlock>>>()
            for (y in 0 until subArrayHeight) {
                val row = mutableListOf<List<ImageBlock>>()
                for (x in 0 until subArrayWidth) {
                    val realX = x + firstTileCoordinate.x
                    val realY = y + firstTileCoordinate.y
                    val tileLayer: TileLayer = resolveTile(data[realY * tiledMap.width.toInt() + realX].toInt(), 0 /* dummy */)
                    val blocks =
                        if (tileLayer is StaticTileImageLayer)
                            listOf(tileLayer.tile)
                        else
                            (tileLayer as AnimationLayer).frames.map { it.imageBlock }
                    row.add(blocks)
                    blocks.forEach {
                        // frame by frame
                        putIntoSrcImageBlocksToDestBlockMapping(listOf(it))
                    }
                }
                dataSubArray.add(row)
            }
            return DynamicSpriteData(firstTileCoordinate, dataSubArray.toList())
        }
    }
}

fun TiledMap.getTileSize() = PixelSize(tilewidth.toInt(), tileheight.toInt())

data class TilesetAndImage(
    val tileset: TiledTileset,
    val image: File,
    val firstgid: Int
)

/**
 * A tile layer may contain a series of animation frames, or a single image block.
 */
interface TileLayer {
    /**
     * Layer above player layer if  index > 0
     * Layer below player layer if  index < 0
     */
    val layer: Int
    fun isFullyOpaque(imageReader: ImageReader): Boolean
    fun isFullyTransparent(imageReader: ImageReader): Boolean
}

class StaticTileImageLayer(
    override val layer: Int,
    val tile: ImageBlock
) : TileLayer {
    override fun isFullyOpaque(imageReader: ImageReader) = imageReader.isFullyOpaque(tile)
    override fun isFullyTransparent(imageReader: ImageReader) = imageReader.isFullyTransparent(tile)
}

data class ImageBlock(
    val image: File,
    val block: PixelBlock
)

class ImageReader {
    private val imageCache: MutableMap<File, BufferedImage> = mutableMapOf()
    private val imageOpaqueCache: MutableMap<ImageBlock, Boolean> = mutableMapOf()
    private val imageTransparencyCache: MutableMap<ImageBlock, Boolean> = mutableMapOf()

    fun isFullyOpaque(imageBlock: ImageBlock): Boolean = imageOpaqueCache.computeIfAbsent(imageBlock) {
        doCheckFullyOpaque(imageBlock)
    }

    private fun doCheckFullyOpaque(imageBlock: ImageBlock): Boolean {
        for (x in 0 until imageBlock.block.width) {
            for (y in 0 until imageBlock.block.height) {
                if (readAlpha(imageBlock.image, imageBlock.block.x + x, imageBlock.block.y + y) < 255) {
                    return false
                }
            }
        }
        return true
    }

    fun getImage(file: File): BufferedImage = imageCache.computeIfAbsent(file) { ImageIO.read(file) }
    fun readAlpha(image: File, x: Int, y: Int): Int = readPixel(image, x, y).a
    fun readPixel(image: File, x: Int, y: Int): RGBA {
        val img = getImage(image)
        return img.getRGBA(x, y)
    }

    private fun BufferedImage.getRGBA(x: Int, y: Int): RGBA {
        return getRGB(x, y).let {
            if (type == BufferedImage.TYPE_BYTE_BINARY)
                RGBA(
                    ((it and 0xff0000) ushr 16),
                    ((it and 0xff00) ushr 8),
                    (it and 0xff),
                    0xff
                )
            else
                RGBA(
                    ((it and 0xff0000) ushr 16),
                    ((it and 0xff00) ushr 8),
                    (it and 0xff),
                    ((it.toLong() and 0xff000000) ushr 24).toInt()
                )
        }
    }

    fun read(image: File): List<List<RGBA>> {
        val img = getImage(image)
        val ret = ArrayList<List<RGBA>>()
        for (y in 0 until img.height) {
            val row = ArrayList<RGBA>()
            for (x in 0 until img.width) {
                row.add(img.getRGBA(x, y))
            }
            ret.add(row)
        }
        return ret
    }

    fun isFullyTransparent(imageBlock: ImageBlock): Boolean = imageTransparencyCache.computeIfAbsent(imageBlock) {
        doCheckFullyTransparent(imageBlock)
    }

    private fun doCheckFullyTransparent(imageBlock: ImageBlock): Boolean {
        for (x in 0 until imageBlock.block.width) {
            for (y in 0 until imageBlock.block.height) {
                if (readAlpha(imageBlock.image, imageBlock.block.x + x, imageBlock.block.y + y) != 0) {
                    return false
                }
            }
        }
        return true
    }
}
