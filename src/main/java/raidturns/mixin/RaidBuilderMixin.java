package raidturns.mixin;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.necro.raid.dens.common.raids.RaidBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.UUID;

@Mixin(value = RaidBuilder.class, remap = false)
public abstract class RaidBuilderMixin {
	/** Raids have no switching: each player fights with one Pokémon. */
	@Redirect(method = "build", at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/api/storage/party/PlayerPartyStore;toBattleTeam(ZZLjava/util/UUID;)Ljava/util/List;"))
	private static List<BattlePokemon> raidturns$onlyLead(PlayerPartyStore party, boolean clone, boolean heal, UUID lead) {
		return party.toBattleTeam(clone, heal, lead).stream().filter(pokemon -> pokemon.getEffectedPokemon().getUuid().equals(lead)).toList();
	}
}
