package io.github.ksaye.mygps

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.graphics.AndroidTileBitmap
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.renderer.DirectRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.io.FileInputStream

/**
 * Renders downloaded country/state maps over the bundled world map.
 *
 * osmdroid's own source merges every file per tile, which (tested) smears the world
 * map's coarse land over harbours, or with "first file wins" paints white blocks
 * wherever a tile straddles a country map's edge, because the country file has no
 * sea outside its own bounds. Here a country map only serves tiles lying entirely
 * inside its bounds; every other tile comes from the world map.
 */
class OfflineTileSource private constructor(
    name: String, detailed: List<File>, world: File, streams: Array<FileInputStream>,
) : MapsForgeTileSource(name, MIN_ZOOM, MAX_ZOOM, TILE_SIZE_PIXELS, streams,
    InternalRenderTheme.OSMARENDER, MultiMapDataStore.DataPolicy.RETURN_FIRST, null, null) {

    private val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_FIRST).apply {
        for (f in detailed) addMapDataStore(ContainedMapFile(f), false, false)
        addMapDataStore(MapFile(world), false, false)
    }
    private val model = DisplayModel()
    private val scale = DisplayModel.getDefaultUserScaleFactor()
    private val renderer = DirectRenderer(store, AndroidGraphicFactory.INSTANCE, true, null)
    private val theme = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, InternalRenderTheme.OSMARENDER, model)
        .also { Thread(it).start() }

    private class ContainedMapFile(file: File) : MapFile(file) {
        override fun supportsTile(tile: Tile): Boolean {
            val m = boundingBox()
            val t = tile.boundingBox
            return m.contains(t.minLatitude, t.minLongitude) && m.contains(t.maxLatitude, t.maxLongitude)
        }
    }

    // Must stay synchronized: MapFile reads aren't thread-safe.
    @Synchronized
    override fun renderTile(pMapTileIndex: Long): Drawable? {
        val tile = Tile(MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex),
            MapTileIndex.getZoom(pMapTileIndex).toByte(), 256)
        model.setFixedTileSize(256)
        return try {
            val bmp = renderer.executeJob(RendererJob(tile, store, theme, model, scale, false, false)) as AndroidTileBitmap?
            bmp?.let { BitmapDrawable(null as android.content.res.Resources?, AndroidGraphicFactory.getBitmap(it)) }
        } catch (e: Exception) {
            null
        }
    }

    override fun addTileRefresher(pDirectTileRefresher: DirectRenderer.TileRefresher?) {
        super.addTileRefresher(pDirectTileRefresher)
        if (pDirectTileRefresher != null) renderer.addTileRefresher(pDirectTileRefresher)
    }

    override fun dispose() {
        super.dispose()
        theme.decrementRefCount()
        store.close()
    }

    companion object {
        /** Detailed maps first (any order), the world map always last. */
        fun create(name: String, detailed: List<File>, world: File) =
            OfflineTileSource(name, detailed, world,
                (detailed + world).map { FileInputStream(it) }.toTypedArray())
    }
}
