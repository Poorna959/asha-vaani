/**
 * Canonical danger-sign list shown on the website.
 * Source: ../docs/danger_signs.json — "NHM ASHA Maternal and Newborn Danger-Sign List" v1.1.
 * Keep in sync with that file; the app escalates ONLY on these signs and never diagnoses.
 */

export const DANGER_SIGN_LIST_VERSION = "1.1";
export const DANGER_SIGN_DISCLAIMER = "Danger-sign referral list only. No diagnosis or treatment advice.";

export type DangerPriority = "IMMEDIATE_EMERGENCY" | "HIGH";

export interface DangerSign {
  code: string;
  name: string;
  priority: DangerPriority;
}

export interface DangerSignGroup {
  id: "MATERNAL_ANC" | "NEWBORN_HBNC";
  title: string;
  subtitle: string;
  signs: DangerSign[];
}

export const DANGER_SIGN_GROUPS: DangerSignGroup[] = [
  {
    id: "MATERNAL_ANC",
    title: "Pregnancy (ANC)",
    subtitle: "Mother",
    signs: [
      { code: "DS_MAT_BLEEDING", name: "Bleeding during pregnancy", priority: "IMMEDIATE_EMERGENCY" },
      { code: "DS_MAT_CONVULSIONS", name: "Convulsions or fits during pregnancy", priority: "IMMEDIATE_EMERGENCY" },
      { code: "DS_MAT_HEADACHE_VISION", name: "Severe headache or blurred vision during pregnancy", priority: "HIGH" },
      { code: "DS_MAT_HIGH_FEVER", name: "High fever during pregnancy", priority: "HIGH" },
      { code: "DS_MAT_NO_FETAL_MOVEMENT", name: "Reduced or absent baby movement", priority: "IMMEDIATE_EMERGENCY" },
      { code: "DS_MAT_WATER_BREAKING", name: "Water breaking or leaking fluid during pregnancy", priority: "IMMEDIATE_EMERGENCY" },
    ],
  },
  {
    id: "NEWBORN_HBNC",
    title: "Newborn (HBNC)",
    subtitle: "Baby",
    signs: [
      { code: "DS_NB_HIGH_FEVER_COLD", name: "High fever or unusually cold body in a newborn", priority: "IMMEDIATE_EMERGENCY" },
      { code: "DS_NB_FAST_BREATHING", name: "Fast breathing or chest in-drawing in a newborn", priority: "IMMEDIATE_EMERGENCY" },
      { code: "DS_NB_POOR_FEEDING", name: "Newborn unable to feed or stopped sucking", priority: "HIGH" },
      { code: "DS_NB_LETHARGY", name: "Lethargic, floppy, or unresponsive newborn", priority: "IMMEDIATE_EMERGENCY" },
      { code: "DS_NB_CONVULSIONS", name: "Convulsions or fits in a newborn", priority: "IMMEDIATE_EMERGENCY" },
      { code: "DS_NB_UMBILICAL_PUS", name: "Pus discharge or spreading redness around the newborn navel", priority: "HIGH" },
    ],
  },
];

export const DANGER_SIGN_COUNT = DANGER_SIGN_GROUPS.reduce((n, g) => n + g.signs.length, 0);
