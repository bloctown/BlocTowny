package com.palmergames.bukkit.towny.war.eventwar.listeners;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.event.damage.TownBlockPVPTestEvent;
import com.palmergames.bukkit.towny.event.damage.TownyFriendlyFireTestEvent;
import com.palmergames.bukkit.towny.event.damage.TownyPlayerDamagePlayerEvent;
import com.palmergames.bukkit.towny.event.deathprice.DeathPriceEvent;
import com.palmergames.bukkit.towny.event.player.PlayerKilledPlayerEvent;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.Translatable;
import com.palmergames.bukkit.towny.object.jail.Jail;
import com.palmergames.bukkit.towny.object.jail.JailReason;
import com.palmergames.bukkit.towny.utils.CombatUtil;
import com.palmergames.bukkit.towny.utils.JailUtil;
import com.palmergames.bukkit.towny.war.eventwar.WarType;
import com.palmergames.bukkit.towny.war.eventwar.WarUtil;
import com.palmergames.bukkit.towny.war.eventwar.instance.War;
import com.palmergames.bukkit.towny.war.eventwar.settings.EventWarSettings;

public class EventWarPVPListener implements Listener {

	@EventHandler
	public void onTownBlockPVPTest(TownBlockPVPTestEvent event) {
		if (!TownyAPI.getInstance().isWarTime())
			return;
		
		if (TownyUniverse.getInstance().hasWarEvent(event.getTownBlock()))
			event.setPvp(true);
	}
	
	@EventHandler
	private void onPlayerKillsPlayer(PlayerKilledPlayerEvent event) {
		Resident killerRes = event.getKillerRes();
		Resident victimRes = event.getVictimRes();
		/*
		 * Quit early if these players don't have the same war.
		 */
		if (!WarUtil.hasSameWar(killerRes, victimRes))
			return;

		War war = TownyUniverse.getInstance().getWarEvent(killerRes);
		
		/*
		 * Handle lives being lost, for wars without unlimited lives.
		 */
		if (war.getWarType().residentLives != -1)
			residentLosesALife(victimRes, killerRes, war, event.getLocation());
		
		/*
		 * Handle war-related death payments. Normal death payments are canceled via
		 * the DeathPriceEvent listener lower down.
		 */
		if (EventWarSettings.isUsingEconomy())
			handleDeathPayments(victimRes, killerRes, war);
		
		/*
		 * If we have jailing attackers enabled, jail those attackers.
		 */
		if (TownySettings.isJailingAttackingEnemies())
			attemptJailingResident(victimRes, killerRes);
	}
	
	private void attemptJailingResident(Resident defenderResident, Resident attackerResident) {
		// Not if the victim or killer has no Town.
		if (!defenderResident.hasTown() || !attackerResident.hasTown())
			return;
		Town attackerTown = attackerResident.getTownOrNull();
		
		// Not if they aren't considered enemies. This should stop RIOT war jailing players. TODO: Make some sort of jail mechanic for RIOT wars.
		if (!CombatUtil.isEnemy(attackerTown, defenderResident.getTownOrNull()))
			return;

		// Attempt to send them to the Town's primary jail first if it is still in the war.
		if (TownyUniverse.getInstance().hasWarEvent(attackerTown.getPrimaryJail().getTownBlock())) {
			JailUtil.jailResident(defenderResident, attackerTown.getPrimaryJail(), 0, JailReason.PRISONER_OF_WAR.getHours(), JailReason.PRISONER_OF_WAR, attackerResident.getPlayer());
			return;
		} else {
		// Find a jail that hasn't had its HP dropped to 0.
			for (Jail jail : attackerTown.getJails()) {
				if (TownyUniverse.getInstance().hasWarEvent(jail.getTownBlock())) {
					// Send to jail. Hours are set later on.
					JailUtil.jailResident(defenderResident, jail, 0, JailReason.PRISONER_OF_WAR.getHours(), JailReason.PRISONER_OF_WAR, attackerResident.getPlayer());
					return;
				}
			}
		}
		// If we've gotten this far the player couldn't be jailed, send a message saying there was no jail.
		TownyMessaging.sendPrefixedTownMessage(attackerTown, Translatable.of("msg_war_player_cant_be_jailed_plot_fallen"));
	}

	/**
	 * Handle death payments.
	 * 
	 * Money is paid by the player, if the player cannot pay the full 
	 * amount the remaining balance is taken from the player's Town.
	 * @param victimRes Resident who will pay.
	 * @param killerRes Resident who will rob the money.
	 * @param war War instance.
	 */
	private void handleDeathPayments(Resident victimRes, Resident killerRes, War war) {

		double price = Math.min(victimRes.getAccount().getHoldingBalance(), EventWarSettings.getWartimeDeathPrice());
		double townPrice = victimRes.getAccount().canPayFromHoldings(price) ? 0 : EventWarSettings.getWartimeDeathPrice() - victimRes.getAccount().getHoldingBalance(); 

		if (price > 0) {
			victimRes.getAccount().payTo(price, killerRes, "Death Payment (War)");
			TownyMessaging.sendMsg(killerRes, Translatable.of("msg_you_robbed_player", victimRes, formatMoney(price)));
			TownyMessaging.sendMsg(victimRes, Translatable.of("msg_player_robbed_you", killerRes, formatMoney(price)));
		}

		/* 
		 * Resident doesn't have enough funds to cover the entire WarTimeDeathPrice.
		 */
		if (townPrice > 0) {

			/*
			 * When it is a RIOT war, the players all have the same Town. 
			 * If the TownPrice is greater than 0, the dead player could not pay
			 * the deathprice. That player will be removed from the war.
			 * The Town never pays during a riot war. 
			 */
			if (war.getWarType().equals(WarType.RIOT)) {
				war.getMessenger().sendGlobalMessage(Translatable.of("msg_player_couldnt_pay_eliminated_riot", victimRes, formatMoney(townPrice)));
				war.getWarParticipants().remove(victimRes);
				return;
			}
			
			/*
			 * We are dealing with a non-RIOT war, where players are on opposing towns.
			 */
			Town victimTown = victimRes.getTownOrNull();
			Town killerTown = killerRes.getTownOrNull();
			double townPricePaid = Math.min(townPrice, victimTown.getAccount().getHoldingBalance());
			if (townPricePaid > 0) {
				TownyMessaging.sendPrefixedTownMessage(victimTown, Translatable.of("msg_player_couldnt_pay_player_town_bank_paying_instead", victimRes, killerRes, formatMoney(townPricePaid)));
				victimTown.getAccount().payTo(townPricePaid, killerRes, String.format("Death Payment (War) (%s couldn't pay)", victimRes));
			}

			/*
			 * Town doesn't have enough money to pay, they pay out 
			 * their bank balance and are removed from the war.
			 */
			if (townPricePaid < townPrice) {
				war.getMessenger().sendGlobalMessage(Translatable.of("msg_town_could_not_pay_death_costs_removing_from_war", victimTown, killerTown, victimTown));
				war.getWarZoneManager().remove(victimTown, killerTown);
			}
		}
	}

	private void residentLosesALife(Resident victimRes, Resident killerRes, War war, Location loc) {
	
		int victimLives = war.getWarParticipants().getLives(victimRes); // Use a variable for this because it will be lost once takeLife(victimRes) is called.

		/*
		 * Take a life off of the victim no matter what type of war it is.
		 */
		war.getWarParticipants().takeLife(victimRes);
		
		/*
		 * Someone is being removed from the war.
		 */
		if (victimLives == 0)
			residentLostLastLife(victimRes, killerRes.getTownOrNull(), war);
	
		/*
		 * Give the killer some points. 
		 */
		if (war.getWarType().pointsPerKill > 0)
			war.getScoreManager().residentScoredKillPoints(victimRes, killerRes, loc);
	}
	
	private void residentLostLastLife(Resident victimRes, Town killerTown, War war) {
		Town victimTown = victimRes.getTownOrNull();
		/*
		 * Remove the resident from the war, handling kings and mayors if monarchdeath is enabled.
		 */
		switch (war.getWarType()) {
		
			case RIOT:
				TownyMessaging.sendPrefixedTownMessage(killerTown, Translatable.of("msg_resident_has_run_out_of_lives_and_is_eliminated_from_the_war", victimRes, war.getWarName()));
				war.getWarParticipants().remove(victimRes);
				war.checkEnd();
				break;
			case NATIONWAR:
			case WORLDWAR:
				/*
				 * Look to see if the king's death would remove a nation from the war.
				 */
				if (war.getWarType().hasMayorDeath && victimRes.isKing()) {
					war.getMessenger().sendGlobalMessage(Translatable.of("MSG_WAR_KING_KILLED", victimRes.getNationOrNull()));
					/*
					 * Remove the king's nation from the war. Where-in the king will be removed with the rest of the residents.
					 */
					war.getWarZoneManager().remove(victimRes.getNationOrNull(), killerTown);
				}
			case CIVILWAR:
			case TOWNWAR:
				/*
				 * Look to see if the mayor's death would remove a town from the war.
				 */
				if (war.getWarType().hasMayorDeath && victimRes.isMayor()) {
					war.getMessenger().sendGlobalMessage(Translatable.of("MSG_WAR_MAYOR_KILLED", victimTown));
					/*
					 * Remove the mayor's town from the war. Where-in the mayor will be removed with the rest of the residents.
					 */
					war.getWarZoneManager().remove(victimTown, killerTown);
	
				/*
				 * Handle regular resident removal when they've run out of lives.	
				 */
				} else {
					war.getMessenger().sendGlobalMessage(Translatable.of("msg_resident_has_run_out_of_lives_and_is_eliminated_from_the_war", victimRes, war.getWarName()));
					war.getWarParticipants().remove(victimRes);
					
					// Test if this was the last resident of the town to have any lives left.
					int residentsWithLives = 0;
					for (Resident res : victimTown.getResidents()) {
						if (war.getWarParticipants().getResidents().contains(res))
							residentsWithLives++;
					}
					if (residentsWithLives == 0)
						war.getWarZoneManager().remove(victimTown, killerTown);
				}
				break;
		}
	}

	@EventHandler
	public void onPlayerDamagePlayer(TownyPlayerDamagePlayerEvent event) {
		if (!TownyAPI.getInstance().isWarTime())
			return;
			
		Town attackerTown = event.getAttackerTown();
		Town defenderTown = event.getVictimTown();
		
		// One Town has no war.
		if (!attackerTown.hasActiveWar() || !defenderTown.hasActiveWar())
			return;
		
		// They might be at war, but is it the same war?
		if (!WarUtil.hasSameWar(event.getAttackingResident(), event.getVictimResident()))
			return;
		
//		//Cancel because one of two players has no town and should not be interfering during war.
//		if (TownySettings.isWarTimeTownsNeutral() && (event.getAttackerTown() == null || event.getVictimTown() == null)){
//			event.setMessage(Translatable.of("msg_war_a_player_has_no_town").forLocale(event.getAttackingPlayer()));
//			event.setCancelled(true);
//			return;
//		}

//		//Cancel because one of the two players' town has no nation and should not be interfering during war.  AND towns_are_neutral is true in the config.
//		if (TownySettings.isWarTimeTownsNeutral() && (!attackerTown.hasNation() || !defenderTown.hasNation())) {
//			event.setMessage(Translatable.of("msg_war_a_player_has_no_nation").forLocale(event.getAttackingPlayer()));
//			event.setCancelled(true);
//			return;
//		}
		
//		//Cancel because one of the two player's nations is neutral.
//		if ((attackerTown.hasNation() && attackerTown.getNationOrNull().isNeutral()) || (defenderTown.hasNation() && defenderTown.getNationOrNull().isNeutral())) {
//			event.setMessage(Translatable.of("msg_war_a_player_has_a_neutral_nation").forLocale(event.getAttackingPlayer()));
//			event.setCancelled(true);
//			return;
//		}
//		
//		//Cancel because one of the two players are no longer involved in the war.
//		if (!TownyUniverse.getInstance().hasWarEvent(defenderTown) || !TownyUniverse.getInstance().hasWarEvent(attackerTown)) {
//			event.setMessage(Translatable.of("msg_war_a_player_has_been_removed_from_war").forLocale(event.getAttackingPlayer()));
//			event.setCancelled(true);
//			return;
//		}
//		
//		//Cancel because one of the two players considers the other an ally.
//		if (CombatUtil.isAlly(attackerTown, defenderTown)){
//			event.setMessage(Translatable.of("msg_war_a_player_is_an_ally").forLocale(event.getAttackingPlayer()));
//			event.setCancelled(true);
//			return;
//		}
	}
	
	@EventHandler
	public void onFriendlyFire(TownyFriendlyFireTestEvent event) {
		if (!TownyAPI.getInstance().isWarTime())
			return;
		
		Resident attacker = TownyAPI.getInstance().getResident(event.getAttacker());
		Resident defender = TownyAPI.getInstance().getResident(event.getDefender());
		
		if (!attacker.hasTown() || !defender.hasTown())
			return;
		
		if (!attacker.getTownOrNull().hasActiveWar() || !defender.getTownOrNull().hasActiveWar())
			return;
		
		if (!WarUtil.hasSameWar(attacker, defender))
			return;
		
		War war = TownyUniverse.getInstance().getWarEvent(attacker);
		
		switch (war.getWarType()) {
			case RIOT:
			case CIVILWAR:
				if ((war.getWarParticipants().getGovSide().contains(attacker) && war.getWarParticipants().getGovSide().contains(defender))
				|| (war.getWarParticipants().getRebSide().contains(attacker) && war.getWarParticipants().getRebSide().contains(defender))) {
					event.setPVP(false);
					return;
				}
				event.setPVP(true);
				break;
			case TOWNWAR:
			case NATIONWAR:
			case WORLDWAR:
				event.setPVP(CombatUtil.isEnemy(attacker.getTownOrNull(), defender.getTownOrNull()));
				break;
		}
	}

	/**
	 * EventWar cancels Towny charging for death when the players are involved in an EventWar together,
	 * handling payments separately in the {@link #onPlayerKillsPlayer(PlayerKilledPlayerEvent)} method.
	 * @param event {@link DeathPriceEvent}.
	 */
	@EventHandler
	public void onDeathPriceEvent(DeathPriceEvent event) {
		if (!event.isPVPDeath())
			return;
		Resident killer = TownyAPI.getInstance().getResident(event.getKiller());
		if (killer == null || !WarUtil.hasSameWar(event.getDeadResident(), killer))
			return;
		
		// Money losses for Event Wars are handled in the onPlayerKillsPlayer method.
		event.setCancelled(true);
	}
	
	private String formatMoney(double money) {
		return TownyEconomyHandler.getFormattedBalance(money);
	}
}
