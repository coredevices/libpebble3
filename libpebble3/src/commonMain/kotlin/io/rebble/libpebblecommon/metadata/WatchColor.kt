package io.rebble.libpebblecommon.metadata

import androidx.compose.ui.graphics.Color

enum class WatchColor(val protocolNumber: Int, val jsName: String, val uiDescription: String, val platform: WatchType, val color: Color = Color.Black) {
    ClassicBlack(1, "pebble_black", "Pebble Classic - Black", WatchType.APLITE),
    ClassicWhite(2, "pebble_white", "Pebble Classic - White", WatchType.APLITE, color = Color.White),
    ClassicRed(3, "pebble_red", "Pebble Classic - Red", WatchType.APLITE, color = Color.Red),
    ClassicOrange(4, "pebble_orange", "Pebble Classic - Orange", WatchType.APLITE, color = Color(0xFFFA4A36)),
    ClassicPink(5, "pebble_pink", "Pebble Classic - Pink", WatchType.APLITE),
    ClassicSteelSilver(6, "pebble_steel_silver", "Pebble Steel - Silver", WatchType.APLITE),
    ClassicSteelGunmetal(7, "pebble_steel_gunmetal", "Pebble Steel - Gunmetal", WatchType.APLITE),
    ClassicFlyBlue(8, "pebble_fly_blue", "Pebble Classic - Fly BLue", WatchType.APLITE, color = Color.Blue),
    ClassicFreshGreen(9, "pebble_fresh_green", "Pebble Classic - Fresh Green", WatchType.APLITE, color = Color.Green),
    ClassicHotPink(10, "pebble_hot_pink", "Pebble Classic - Hot Pink", WatchType.APLITE),
    TimeWhite(11, "pebble_time_white", "Pebble Time - White", WatchType.BASALT, color = Color.White),
    TimeBlack(12, "pebble_time_black", "Pebble Time - Black", WatchType.BASALT),
    TimeRed(13, "pebble_time_red", "Pebble Time - Red", WatchType.BASALT, color = Color.Red),
    TimeSteelSilver(14, "pebble_time_steel_silver", "Pebble Time Steel - Silver", WatchType.BASALT),
    TimeSteelGunmetal(15, "pebble_time_steel_black", "Pebble Time Steel - Black", WatchType.BASALT),
    TimeSteelGold(16, "pebble_time_steel_gold", "Pebble Time Steel - Gold", WatchType.BASALT),
    TimeRoundSilver14(17, "pebble_time_round_silver", "Pebble Time Round - Silver", WatchType.CHALK),
    TimeRoundBlack14(18, "pebble_time_round_black", "Pebble Time Round - Black", WatchType.CHALK),
    TimeRoundSilver20(19, "pebble_time_round_silver_20", "Pebble Time Round - Silver", WatchType.CHALK),
    TimeRoundBlack20(20, "pebble_time_round_black_20", "Pebble Time Round - Black", WatchType.CHALK),
    TimeRoundRoseGold14(21, "pebble_time_round_rose_gold", "Pebble Time Round - Rose Gold", WatchType.CHALK),
    TimeRoundRainbowSilver14(22, "pebble_time_round_silver_rainbow", "Pebble Time Round - Silver Rainbow", WatchType.CHALK),
    TimeRoundRainbowBlack20(23, "pebble_time_round_black_rainbow", "Pebble Time Round - Black Rainbow", WatchType.CHALK),
    Pebble2SEBlack(24, "pebble_2_se_black_charcoal", "Pebble 2 SE - Black Charcoal", WatchType.DIORITE),
    Pebble2HRBlack(25, "pebble_2_hr_black_charcoal", "Pebble 2 HR - Black Charcoal", WatchType.DIORITE),
    Pebble2SEWhite(26, "pebble_2_se_white_gray", "Pebble 2 SE - White/Gray", WatchType.DIORITE, color = Color.White),
    Pebble2HRLime(27, "pebble_2_hr_charcoal_sorbet_green", "Pebble 2 HR - Sorbet Green", WatchType.DIORITE),
    Pebble2HRFlame(28, "pebble_2_hr_charcoal_red", "Pebble 2 HR - Charcoal Red", WatchType.DIORITE, color = Color.Red),
    Pebble2HRWhite(29, "pebble_2_hr_white_gray", "Pebble 2 HR - White Gray", WatchType.DIORITE, color = Color.White),
    Pebble2HRAqua(30, "pebble_2_hr_white_turquoise", "Pebble 2 HR - White Turquoise", WatchType.DIORITE),
    Time2Gunmetal(31, "pebble_time_2_black", "Pebble Time 2 - Black", WatchType.EMERY),
    Time2Silver(32, "pebble_time_2_silver", "Pebble Time 2 - Silver", WatchType.EMERY),
    Time2Gold(33, "pebble_time_2_gold", "Pebble Time 2 - Gold", WatchType.EMERY),
    Pebble2DuoBlack(34, "pebble_2_duo_black", "Pebble 2 Duo - Black", WatchType.FLINT),
    Pebble2DuoWhite(35, "pebble_2_duo_white", "Pebble 2 Duo - White", WatchType.FLINT, color = Color.White),
    PebbleTime2Black(36, "pebble_time_2_black", "Pebble Time 2 - Black", WatchType.EMERY),
    Unknown(-1, "unknown_unknown", "Unknown Watch!", WatchType.APLITE);

    companion object {
        fun fromProtocolNumber(protocolNumber: Int?): WatchColor {
            return entries.firstOrNull { it.protocolNumber == protocolNumber } ?: Unknown
        }
    }
}