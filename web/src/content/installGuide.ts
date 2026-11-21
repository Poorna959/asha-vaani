/**
 * Step-by-step APK install guide in the five languages the app supports.
 * Mirrors SupportedLanguage in ../android/.../model/DomainModels.kt.
 * Indic translations should be reviewed by native speakers before wide distribution.
 */

export type GuideLanguage = "en" | "hi" | "kn" | "ta" | "te";

export interface GuideStep {
  title: string;
  body: string;
}

export interface InstallGuide {
  lang: GuideLanguage;
  /** BCP-47 tag used for the lang attribute (fonts + screen readers). */
  bcp47: string;
  label: string;
  heading: string;
  steps: GuideStep[];
  troubleshootingTitle: string;
  troubleshooting: GuideStep[];
}

export const INSTALL_GUIDES: InstallGuide[] = [
  {
    lang: "en",
    bcp47: "en-IN",
    label: "English",
    heading: "How to install ASHA Vaani",
    steps: [
      {
        title: "Download the app",
        body: "Tap “Download APK”. If Chrome says the file might be harmful, tap “Download anyway”.",
      },
      {
        title: "Open the downloaded file",
        body: "Tap the download notification, or open Files → Downloads and tap the ASHA Vaani file.",
      },
      {
        title: "Allow installing from this source",
        body: "If asked, tap “Settings”, turn on “Allow from this source”, then press Back.",
      },
      {
        title: "Install and open",
        body: "Tap “Install”. If Play Protect shows a warning, tap “More details” → “Install anyway”. Then tap “Open”.",
      },
      {
        title: "Allow the microphone",
        body: "When ASHA Vaani asks for microphone permission, tap “Allow” so it can hear your visit notes.",
      },
    ],
    troubleshootingTitle: "Having trouble?",
    troubleshooting: [
      {
        title: "“App not installed”",
        body: "An older copy signed differently may already be on the phone. Make sure all visits have synced, uninstall the old app, then install again.",
      },
      {
        title: "Updating to a new version",
        body: "Download the new version from this page and install it over the old one. You do not need to uninstall first.",
      },
    ],
  },
  {
    lang: "hi",
    bcp47: "hi-IN",
    label: "हिन्दी",
    heading: "ASHA Vaani कैसे इंस्टॉल करें",
    steps: [
      {
        title: "ऐप डाउनलोड करें",
        body: "“Download APK” पर टैप करें। अगर Chrome कहे कि फ़ाइल नुकसानदायक हो सकती है, तो “फिर भी डाउनलोड करें” पर टैप करें।",
      },
      {
        title: "डाउनलोड की गई फ़ाइल खोलें",
        body: "डाउनलोड नोटिफ़िकेशन पर टैप करें, या Files → Downloads खोलकर ASHA Vaani फ़ाइल पर टैप करें।",
      },
      {
        title: "इस स्रोत से इंस्टॉल की अनुमति दें",
        body: "अगर पूछा जाए, तो “Settings” पर टैप करें, “इस स्रोत से अनुमति दें” चालू करें, फिर Back दबाएँ।",
      },
      {
        title: "इंस्टॉल करें और खोलें",
        body: "“Install” पर टैप करें। अगर Play Protect चेतावनी दिखाए, तो “More details” → “फिर भी इंस्टॉल करें” पर टैप करें। फिर “Open” पर टैप करें।",
      },
      {
        title: "माइक्रोफ़ोन की अनुमति दें",
        body: "जब ASHA Vaani माइक्रोफ़ोन की अनुमति माँगे, तो “Allow” पर टैप करें ताकि ऐप आपकी विज़िट की बातें सुन सके।",
      },
    ],
    troubleshootingTitle: "कोई परेशानी?",
    troubleshooting: [
      {
        title: "“App not installed” दिखे",
        body: "फ़ोन में ऐप का कोई पुराना वर्शन हो सकता है। पहले देख लें कि सभी विज़िट सिंक हो गई हैं, पुराना ऐप अनइंस्टॉल करें, फिर दोबारा इंस्टॉल करें।",
      },
      {
        title: "नए वर्शन में अपडेट करना",
        body: "इसी पेज से नया वर्शन डाउनलोड करें और पुराने के ऊपर इंस्टॉल करें। पहले अनइंस्टॉल करने की ज़रूरत नहीं है।",
      },
    ],
  },
  {
    lang: "kn",
    bcp47: "kn-IN",
    label: "ಕನ್ನಡ",
    heading: "ASHA Vaani ಅನ್ನು ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡುವುದು ಹೇಗೆ",
    steps: [
      {
        title: "ಆ್ಯಪ್ ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ",
        body: "“Download APK” ಒತ್ತಿ. ಫೈಲ್ ಹಾನಿಕಾರಕವಾಗಿರಬಹುದು ಎಂದು Chrome ಹೇಳಿದರೆ, “ಆದರೂ ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ” ಒತ್ತಿ.",
      },
      {
        title: "ಡೌನ್‌ಲೋಡ್ ಆದ ಫೈಲ್ ತೆರೆಯಿರಿ",
        body: "ಡೌನ್‌ಲೋಡ್ ನೋಟಿಫಿಕೇಶನ್ ಒತ್ತಿ, ಅಥವಾ Files → Downloads ತೆರೆದು ASHA Vaani ಫೈಲ್ ಒತ್ತಿ.",
      },
      {
        title: "ಈ ಮೂಲದಿಂದ ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಲು ಅನುಮತಿಸಿ",
        body: "ಕೇಳಿದರೆ, “Settings” ಒತ್ತಿ, “ಈ ಮೂಲದಿಂದ ಅನುಮತಿಸಿ” ಆನ್ ಮಾಡಿ, ನಂತರ Back ಒತ್ತಿ.",
      },
      {
        title: "ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ ಮತ್ತು ತೆರೆಯಿರಿ",
        body: "“Install” ಒತ್ತಿ. Play Protect ಎಚ್ಚರಿಕೆ ತೋರಿಸಿದರೆ, “More details” → “ಆದರೂ ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ” ಒತ್ತಿ. ನಂತರ “Open” ಒತ್ತಿ.",
      },
      {
        title: "ಮೈಕ್ರೋಫೋನ್‌ಗೆ ಅನುಮತಿ ನೀಡಿ",
        body: "ASHA Vaani ಮೈಕ್ರೋಫೋನ್ ಅನುಮತಿ ಕೇಳಿದಾಗ “Allow” ಒತ್ತಿ, ಆಗ ಆ್ಯಪ್ ನಿಮ್ಮ ಭೇಟಿಯ ಮಾತುಗಳನ್ನು ಕೇಳಬಹುದು.",
      },
    ],
    troubleshootingTitle: "ತೊಂದರೆ ಇದೆಯೇ?",
    troubleshooting: [
      {
        title: "“App not installed” ಎಂದು ಬಂದರೆ",
        body: "ಫೋನ್‌ನಲ್ಲಿ ಹಳೆಯ ಆವೃತ್ತಿ ಇರಬಹುದು. ಎಲ್ಲಾ ಭೇಟಿಗಳು ಸಿಂಕ್ ಆಗಿವೆಯೇ ಎಂದು ನೋಡಿ, ಹಳೆಯ ಆ್ಯಪ್ ಅನ್‌ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ, ನಂತರ ಮತ್ತೆ ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ.",
      },
      {
        title: "ಹೊಸ ಆವೃತ್ತಿಗೆ ಅಪ್‌ಡೇಟ್",
        body: "ಇದೇ ಪುಟದಿಂದ ಹೊಸ ಆವೃತ್ತಿ ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ ಹಳೆಯದರ ಮೇಲೆಯೇ ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ. ಮೊದಲು ಅನ್‌ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡುವ ಅಗತ್ಯವಿಲ್ಲ.",
      },
    ],
  },
  {
    lang: "ta",
    bcp47: "ta-IN",
    label: "தமிழ்",
    heading: "ASHA Vaani-ஐ நிறுவுவது எப்படி",
    steps: [
      {
        title: "ஆப்பைப் பதிவிறக்கவும்",
        body: "“Download APK” ஐத் தட்டவும். கோப்பு தீங்கானதாக இருக்கலாம் என்று Chrome சொன்னால், “எப்படியும் பதிவிறக்கு” என்பதைத் தட்டவும்.",
      },
      {
        title: "பதிவிறக்கிய கோப்பைத் திறக்கவும்",
        body: "பதிவிறக்க அறிவிப்பைத் தட்டவும், அல்லது Files → Downloads திறந்து ASHA Vaani கோப்பைத் தட்டவும்.",
      },
      {
        title: "இந்த மூலத்திலிருந்து நிறுவ அனுமதிக்கவும்",
        body: "கேட்கப்பட்டால், “Settings” ஐத் தட்டி, “இந்த மூலத்திலிருந்து அனுமதி” என்பதை இயக்கி, பின் Back அழுத்தவும்.",
      },
      {
        title: "நிறுவித் திறக்கவும்",
        body: "“Install” ஐத் தட்டவும். Play Protect எச்சரிக்கை காட்டினால், “More details” → “எப்படியும் நிறுவு” என்பதைத் தட்டவும். பின் “Open” ஐத் தட்டவும்.",
      },
      {
        title: "மைக்ரோஃபோனை அனுமதிக்கவும்",
        body: "ASHA Vaani மைக்ரோஃபோன் அனுமதி கேட்கும்போது “Allow” ஐத் தட்டவும்; அப்போதுதான் உங்கள் வருகைக் குறிப்புகளை அது கேட்க முடியும்.",
      },
    ],
    troubleshootingTitle: "சிக்கல் உள்ளதா?",
    troubleshooting: [
      {
        title: "“App not installed” என்று வந்தால்",
        body: "போனில் பழைய பதிப்பு இருக்கலாம். எல்லா வருகைகளும் ஒத்திசைந்ததை உறுதிசெய்து, பழைய ஆப்பை நீக்கி, மீண்டும் நிறுவவும்.",
      },
      {
        title: "புதிய பதிப்புக்கு மேம்படுத்த",
        body: "இதே பக்கத்திலிருந்து புதிய பதிப்பைப் பதிவிறக்கி பழையதன் மேலேயே நிறுவவும். முதலில் நீக்கத் தேவையில்லை.",
      },
    ],
  },
  {
    lang: "te",
    bcp47: "te-IN",
    label: "తెలుగు",
    heading: "ASHA Vaani ని ఇన్‌స్టాల్ చేయడం ఎలా",
    steps: [
      {
        title: "యాప్‌ను డౌన్‌లోడ్ చేయండి",
        body: "“Download APK” నొక్కండి. ఫైల్ హానికరం కావచ్చని Chrome చెబితే, “అయినా డౌన్‌లోడ్ చేయి” నొక్కండి.",
      },
      {
        title: "డౌన్‌లోడ్ అయిన ఫైల్‌ను తెరవండి",
        body: "డౌన్‌లోడ్ నోటిఫికేషన్ నొక్కండి, లేదా Files → Downloads తెరిచి ASHA Vaani ఫైల్ నొక్కండి.",
      },
      {
        title: "ఈ మూలం నుండి ఇన్‌స్టాల్ చేయడానికి అనుమతించండి",
        body: "అడిగితే, “Settings” నొక్కి, “ఈ మూలం నుండి అనుమతించు” ఆన్ చేసి, ఆ తర్వాత Back నొక్కండి.",
      },
      {
        title: "ఇన్‌స్టాల్ చేసి తెరవండి",
        body: "“Install” నొక్కండి. Play Protect హెచ్చరిక చూపిస్తే, “More details” → “అయినా ఇన్‌స్టాల్ చేయి” నొక్కండి. తర్వాత “Open” నొక్కండి.",
      },
      {
        title: "మైక్రోఫోన్‌ను అనుమతించండి",
        body: "ASHA Vaani మైక్రోఫోన్ అనుమతి అడిగినప్పుడు “Allow” నొక్కండి, అప్పుడు యాప్ మీ సందర్శన నోట్స్ వినగలదు.",
      },
    ],
    troubleshootingTitle: "సమస్య ఉందా?",
    troubleshooting: [
      {
        title: "“App not installed” వస్తే",
        body: "ఫోన్‌లో పాత వెర్షన్ ఉండవచ్చు. అన్ని సందర్శనలు సింక్ అయ్యాయో చూసుకుని, పాత యాప్‌ను అన్‌ఇన్‌స్టాల్ చేసి, మళ్లీ ఇన్‌స్టాల్ చేయండి.",
      },
      {
        title: "కొత్త వెర్షన్‌కు అప్‌డేట్",
        body: "ఇదే పేజీ నుండి కొత్త వెర్షన్ డౌన్‌లోడ్ చేసి పాతదానిపైనే ఇన్‌స్టాల్ చేయండి. ముందుగా అన్‌ఇన్‌స్టాల్ చేయాల్సిన అవసరం లేదు.",
      },
    ],
  },
];

export const DEFAULT_GUIDE_LANGUAGE: GuideLanguage = "en";

/** Picks the best guide for the browser's preferred languages (e.g. ["kn-IN", "en"]). */
export function pickGuideLanguage(preferred: readonly string[]): GuideLanguage {
  for (const tag of preferred) {
    const base = tag.toLowerCase().split("-")[0];
    const match = INSTALL_GUIDES.find((g) => g.lang === base);
    if (match) return match.lang;
  }
  return DEFAULT_GUIDE_LANGUAGE;
}
