package dev.rflab.scanner

/** Camera2 records before MSCKF initializes; reserve DA3 computation for
 * genuine timestamp-associated native camera poses. Existing RGB JPEGs
 * remain accessible unchanged in the photo session.
 */
internal object VioKeyframeSelection {
    fun selectIndices(photoTimestamps:List<Long>,
                      poses:List<ExternalVioTrajectory.Sample>,
                      limit:Int=24):List<Int> {
        require(limit>=1)
        val matched=if(poses.isEmpty())emptyList() else
            photoTimestamps.indices.filter{
                ExternalVioTrajectory.closestIndex(
                    poses,photoTimestamps[it])>=0
            }
        val candidates=matched.ifEmpty{photoTimestamps.indices.toList()}
        if(candidates.size<=limit)return candidates
        if(limit==1)return listOf(candidates[candidates.size/2])
        return (0 until limit).map{index->
            candidates[index*(candidates.size-1)/(limit-1)]
        }.distinct()
    }
}
