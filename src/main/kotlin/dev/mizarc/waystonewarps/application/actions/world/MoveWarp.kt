package dev.mizarc.waystonewarps.application.actions.world

import dev.mizarc.waystonewarps.application.results.MoveWarpResult
import dev.mizarc.waystonewarps.application.services.ConfigService
import dev.mizarc.waystonewarps.application.services.HologramService
import dev.mizarc.waystonewarps.application.services.StructureBuilderService
import dev.mizarc.waystonewarps.application.services.StructureParticleService
import dev.mizarc.waystonewarps.application.services.WarpEventPublisher
import dev.mizarc.waystonewarps.domain.positioning.Position3D
import dev.mizarc.waystonewarps.domain.warps.WarpRepository
import java.util.UUID
import kotlin.math.sqrt

class MoveWarp(private val warpRepository: WarpRepository,
               private val structureBuilderService: StructureBuilderService,
               private val structureParticleService: StructureParticleService,
               private val hologramService: HologramService,
               private val warpEventPublisher: WarpEventPublisher,
               private val configService: ConfigService
) {
    // Nether distances travel at 8x their overworld equivalent (matches vanilla portal linking).
    private val netherToOverworldScale = 8.0

    fun execute(
        playerId: UUID,
        warpId: UUID,
        worldId: UUID,
        position: Position3D,
        isSourceWorldNether: Boolean,
        isDestinationWorldNether: Boolean,
        bypassOwnership: Boolean = false
    ): MoveWarpResult {
        val warp = warpRepository.getById(warpId) ?: return MoveWarpResult.WARP_NOT_FOUND

        if (warp.playerId != playerId && !bypassOwnership) {
            return MoveWarpResult.NOT_OWNER
        }

        // Caps how far a "move item" can be placed from a warp's current location. Without this,
        // moving a warp is an unlimited-distance, instant, free teleport for the warp (and for
        // anyone who has already discovered it) - see the "move item as free travel" exploit.
        // Bypasses ownership permission holders are trusted to relocate freely (e.g. for cleanup).
        if (!bypassOwnership) {
            val distance = overworldEquivalentDistance(
                warp.position, isSourceWorldNether,
                position, isDestinationWorldNether
            )

            if (distance > configService.getWarpMoveRadius()) {
                return MoveWarpResult.TOO_FAR
            }
        }

        structureBuilderService.destroyStructure(warp)
        val oldWarp = warp.copy()
        warp.worldId = worldId
        warp.position = position
        structureBuilderService.spawnStructure(warp)
        warpRepository.update(warp)
        hologramService.updateHologram(warp)
        structureParticleService.removeParticles(warp)
        structureParticleService.spawnParticles(warp)
        warpEventPublisher.warpModified(oldWarp, warp)
        return MoveWarpResult.SUCCESS
    }

    /**
     * Straight-line distance between two positions, first converting any Nether-side coordinates
     * to their overworld-equivalent (x8 on the horizontal axes only - Y isn't scaled by portal
     * linking). This lets a short physical walk between linked overworld/nether points stay
     * within the radius, while still capping the overworld-equivalent distance a warp can travel.
     */
    private fun overworldEquivalentDistance(
        from: Position3D,
        fromIsNether: Boolean,
        to: Position3D,
        toIsNether: Boolean
    ): Double {
        val fromScale = if (fromIsNether) netherToOverworldScale else 1.0
        val toScale = if (toIsNether) netherToOverworldScale else 1.0

        val dx = (to.x * toScale) - (from.x * fromScale)
        val dz = (to.z * toScale) - (from.z * fromScale)
        val dy = (to.y - from.y).toDouble()

        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
