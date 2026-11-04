// =====================================================================
//  ColdPull Station — параметричний корпус (OpenSCAD)
//
//  Деталі (оберіть part і експортуйте STL: F6 → F7):
//    "body"    — корпус (друкувати ДОГОРИ ДНОМ, верхньою площиною на стіл)
//    "lid"     — нижня кришка з вентиляцією
//    "adapter" — змінна пластина під конкретний хотенд
//    "fan"     — кронштейн вентилятора 40 мм для радіатора хотенда
//    "all"     — збірка для перегляду
//
//  Матеріал: PETG / ABS / ASA. Адаптер — ASA/PC (поруч гарячий хотенд).
//  PLA не використовуйте.
// =====================================================================

part = "all";  // [all, body, lid, adapter, fan]

/* [Корпус] */
W      = 110;   // ширина (X)
D      = 80;    // глибина (Y)
H      = 70;    // висота (Z)
wall   = 2.4;
top_t  = 3;     // товщина верху над електронікою
floor_t = 4;    // товщина дна ніші

/* [Ніша для хотенда] */
bay_w  = 44;    // ширина ніші
bay_d  = 45;    // глибина ніші
roof_t = 6;     // товщина "даху" ніші (тут стоїть адаптер)
tray   = [40, 36, 1.2];  // поглиблення під силіконовий/PTFE килимок

/* [Гніздо адаптера] */
pocket   = [40, 40];  // гніздо під адаптер (відкрите спереду)
pocket_h = 4;         // глибина гнізда = товщина адаптера
thru_w   = 32;        // наскрізний проріз у даху

/* [Адаптер під хотенд — ВИМІРЯЙТЕ ШТАНГЕНЦИРКУЛЕМ] */
neck_w   = 14;   // ширина прорізу під шийку (між радіатором і нагрівачем)
hs_w     = 26;   // ширина основи радіатора
hs_d     = 26;   // глибина основи радіатора
hs_rec   = 1.6;  // глибина посадки радіатора в адаптер
hotend_y = 18;   // центр хотенда від переднього краю адаптера

/* [Передня панель] */
oled_win  = [25, 14];     // вікно під OLED 0.96"
oled_hole = [23.5, 23.8]; // відстань між отворами M2 модуля (перевірте!)
oled_z    = 50;
enc_d     = 7.5;          // отвір під вал енкодера KY-040
enc_z     = 22;

/* [Задня панель] */
jack_d    = 8.2;          // гніздо 5.5x2.1 під гайку M8
switch_sz = [19.4, 13.2]; // клавішний вимикач KCD1

/* [Кріплення] */
m3_tap  = 2.7;  // під саморіз/різьбу M3
m3_free = 3.4;
boss_d  = 7;
boss_h  = 10;

$fn = 48;
e = 0.01;

// ---------------------------------------------------------------------
elec_x0 = bay_w + 2 * wall;          // ліва межа відсіку електроніки
front_cx = (bay_w + wall + W) / 2;   // центр передньої панелі електроніки
bay_cx = wall + bay_w / 2;           // вісь хотенда
bay_h = H - floor_t - roof_t;

module rcube(s, r = 2) {  // куб із заокругленими вертикальними ребрами
  hull() for (x = [r, s[0] - r], y = [r, s[1] - r]) translate([x, y, 0]) cylinder(r = r, h = s[2]);
}

boss_pos = [
  [W - wall - boss_d / 2, wall + boss_d / 2],
  [W - wall - boss_d / 2, D - wall - boss_d / 2],
  [wall + boss_d / 2, D - wall - boss_d / 2],
  [elec_x0 + boss_d / 2, wall + boss_d / 2],
];

fan_screw_y = bay_d + wall + 6;
fan_screws = [[bay_cx - 14, fan_screw_y], [bay_cx + 14, fan_screw_y]];
cable_hole = [bay_cx, bay_d + wall + 16];

// ---------------------------------------------------------------------
module body() {
  difference() {
    union() {
      // оболонка
      difference() {
        rcube([W, D, H], 3);
        translate([wall, wall, -e]) cube([W - 2 * wall, D - 2 * wall, H - top_t + e]);
      }
      // суцільний блок ніші (з нього вирізаємо саму нішу)
      cube([bay_w + 2 * wall, bay_d + wall, H]);
      // бобишки під гвинти кришки
      for (p = boss_pos) translate([p[0], p[1], 0]) cylinder(d = boss_d, h = boss_h);
      // внутрішні ребра під модуль OLED (стійки)
      for (sx = [-1, 1], sz = [-1, 1])
        translate([front_cx + sx * oled_hole[0] / 2, wall, oled_z + sz * oled_hole[1] / 2])
          rotate([-90, 0, 0]) cylinder(d = 5, h = 3);
    }

    // ніша
    translate([wall, -e, floor_t]) cube([bay_w, bay_d + e, bay_h]);
    // поглиблення під килимок
    translate([bay_cx - tray[0] / 2, 2, floor_t - tray[2]]) cube([tray[0], tray[1], tray[2] + e]);

    // гніздо адаптера (відкрите спереду) + наскрізний проріз
    translate([bay_cx - pocket[0] / 2, -e, H - pocket_h]) cube([pocket[0], pocket[1] + e, pocket_h + e]);
    translate([bay_cx - thru_w / 2, -e, H - roof_t - e]) cube([thru_w, pocket[1] - 6, roof_t + 2 * e]);

    // кронштейн вентилятора і кабельний отвір
    for (p = fan_screws) translate([p[0], p[1], H - top_t - 1]) cylinder(d = m3_tap, h = top_t + 2);
    translate([cable_hole[0], cable_hole[1], H - top_t - 1]) cylinder(d = 10, h = top_t + 2);

    // отвори під гвинти кришки
    for (p = boss_pos) translate([p[0], p[1], -e]) cylinder(d = m3_tap, h = boss_h - 1);

    // --- передня панель ---
    // вікно OLED (з фаскою назовні)
    translate([front_cx, -e, oled_z + 1.5])
      hull() {
        translate([-oled_win[0] / 2, 0, -oled_win[1] / 2]) cube([oled_win[0], e, oled_win[1]]);
        translate([-oled_win[0] / 2 + 0.8, wall - 0.8, -oled_win[1] / 2 + 0.8]) cube([oled_win[0] - 1.6, 0.8 + e, oled_win[1] - 1.6]);
      }
    for (sx = [-1, 1], sz = [-1, 1])
      translate([front_cx + sx * oled_hole[0] / 2, wall - e, oled_z + sz * oled_hole[1] / 2])
        rotate([-90, 0, 0]) cylinder(d = 1.8, h = 4);
    // енкодер
    translate([front_cx, -e, enc_z]) rotate([-90, 0, 0]) cylinder(d = enc_d, h = wall + 2 * e);
    // заглиблення під ручку
    translate([front_cx, -e, enc_z]) rotate([-90, 0, 0]) cylinder(d = 24, h = 0.6);

    // --- задня панель ---
    translate([W - 22, D - wall - e, 20]) rotate([-90, 0, 0]) cylinder(d = jack_d, h = wall + 2 * e);
    translate([W - 52 - switch_sz[0] / 2, D - wall - e, 20 - switch_sz[1] / 2]) cube([switch_sz[0], wall + 2 * e, switch_sz[1]]);
    translate([W - 22, D - 0.6, 30]) rotate([90, 0, 180])
      linear_extrude(0.6 + e) text("24V", size = 5, halign = "center", font = "Liberation Sans:style=Bold");

    // --- вентиляційні щілини праворуч ---
    for (i = [0 : 5]) translate([W - wall - e, 18 + i * 8, 14]) cube([wall + 2 * e, 3, 30]);
  }
}

// ---------------------------------------------------------------------
module lid() {
  t = 2.4;
  difference() {
    union() {
      rcube([W, D, t], 3);
      // бортик, що заходить у корпус (тільки у відсіку електроніки)
      translate([elec_x0 + 0.3, wall + 0.3, t]) difference() {
        cube([W - wall - elec_x0 - 0.6, D - 2 * wall - 0.6, 3]);
        translate([1.6, 1.6, -e]) cube([W - wall - elec_x0 - 3.8, D - 2 * wall - 3.8, 3 + 2 * e]);
      }
    }
    for (p = boss_pos) translate([p[0], p[1], -e]) {
      cylinder(d = m3_free, h = 20);
      cylinder(d1 = 6.4, d2 = m3_free, h = 1.6);  // потай
    }
    // вентиляція
    for (i = [0 : 6]) translate([elec_x0 + 10 + i * 6.5, 16, -e]) cube([3, D - 32, t + 2 * e]);
    // гнізда під гумові ніжки 10 мм
    for (p = [[10, 10], [W - 10, 10], [10, D - 10], [W - 10, D - 10]])
      translate([p[0], p[1], -e]) cylinder(d = 10.5, h = 0.8);
  }
}

// ---------------------------------------------------------------------
module adapter() {
  c = 0.3;  // зазор у гнізді
  a = [pocket[0] - 2 * c, pocket[1] - c, pocket_h];
  difference() {
    translate([-a[0] / 2, 0, 0]) cube(a);
    // U-проріз під шийку хотенда, відкритий спереду
    hull() {
      translate([-neck_w / 2, -e, -e]) cube([neck_w, e, a[2] + 2 * e]);
      translate([0, hotend_y, -e]) cylinder(d = neck_w, h = a[2] + 2 * e);
    }
    // посадка під основу радіатора
    translate([-hs_w / 2, hotend_y - hs_d / 2, a[2] - hs_rec]) cube([hs_w, hs_d, hs_rec + e]);
    translate([-hs_w / 2, -e, a[2] - hs_rec]) cube([hs_w, hotend_y + e, hs_rec + e]);
    // виїмка для пальця, щоб легко вийняти
    translate([0, a[1] - 2, a[2]]) rotate([0, 90, 0]) cylinder(d = 5, h = a[0] - 8, center = true);
  }
}

// ---------------------------------------------------------------------
module fan_bracket() {
  fan = 40; hole = 32; plate = 3;
  difference() {
    union() {
      translate([-fan / 2 - 2, 0, 0]) cube([fan + 4, 12, 4]);                // основа
      translate([-fan / 2 - 2, 0, 0]) cube([fan + 4, plate, fan + 8]);       // панель
      for (sx = [-1, 1]) translate([sx * (fan / 2 - 1) - 1.5, 0, 0])          // ребра
        hull() { cube([3, 12, 4]); cube([3, plate, 20]); }
    }
    // отвір під потік і гвинти вентилятора
    translate([0, -e, 8 + fan / 2]) rotate([-90, 0, 0]) cylinder(d = fan - 2, h = plate + 2 * e);
    for (sx = [-1, 1], sz = [-1, 1])
      translate([sx * hole / 2, -e, 8 + fan / 2 + sz * hole / 2]) rotate([-90, 0, 0]) cylinder(d = m3_tap, h = plate + 2 * e);
    // кріплення до корпусу (координати збігаються з fan_screws)
    for (sx = [-1, 1]) translate([sx * 14, 6, -e]) cylinder(d = m3_free, h = 5);
  }
}

// ---------------------------------------------------------------------
if (part == "body") rotate([180, 0, 0]) body();       // друк догори дном
else if (part == "lid") lid();
else if (part == "adapter") adapter();
else if (part == "fan") fan_bracket();
else {
  color("DimGray") body();
  color("SlateGray") translate([0, 0, -2.4 - 6]) lid();
  color("Orange") translate([bay_cx, 0.3, H - pocket_h]) adapter();
  color("DarkSlateGray") translate([bay_cx, fan_screw_y - 6, H]) fan_bracket();
}
