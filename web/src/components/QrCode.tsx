import { useEffect, useState } from "react";
import QRCode from "qrcode";

interface QrCodeProps {
  value: string;
  size?: number;
  label?: string;
}

/**
 * Generates an inline SVG QR code locally in the browser — never calls an external QR service.
 */
export function QrCode({ value, size = 168, label = "QR code" }: QrCodeProps) {
  const [svg, setSvg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    QRCode.toString(value, {
      type: "svg",
      width: size,
      margin: 1,
      errorCorrectionLevel: "M",
      color: { dark: "#0F172A", light: "#FFFFFF" },
    })
      .then((str) => {
        if (!cancelled) setSvg(str);
      })
      .catch(() => {
        if (!cancelled) setSvg(null);
      });
    return () => {
      cancelled = true;
    };
  }, [value, size]);

  if (!svg) {
    return <span className="skeleton" style={{ width: size, height: size, borderRadius: 12 }} aria-hidden="true" />;
  }

  return (
    <div
      role="img"
      aria-label={label}
      style={{ width: size, height: size, borderRadius: 12, overflow: "hidden", border: "1.5px solid var(--border)" }}
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  );
}
