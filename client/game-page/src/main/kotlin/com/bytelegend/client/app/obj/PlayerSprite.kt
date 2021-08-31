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
package com.bytelegend.client.app.obj

import com.bytelegend.app.client.api.GameScene
import com.bytelegend.app.shared.GridCoordinate
import com.bytelegend.app.shared.entities.BasePlayer
import com.bytelegend.app.shared.objects.GameObjectRole
import com.bytelegend.client.utils.jsObjectBackedSetOf

fun playerSpriteId(playerId: String) = "player-$playerId-sprite"

open class PlayerSprite(
    gameScene: GameScene,
    val player: BasePlayer,
) : CharacterSprite(
    gameScene,
    GridCoordinate(player.x, player.y) * gameScene.map.tileSize,
    TwelveTilesAnimationSet(gameScene, player.characterId)
) {
    override val id: String = playerSpriteId(player.id)
    override val roles: Set<String> = jsObjectBackedSetOf(
        GameObjectRole.Sprite,
        GameObjectRole.Character
    )
}
