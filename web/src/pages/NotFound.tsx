import { Link } from "react-router";
import { useDocumentTitle } from "../hooks/useDocumentTitle";

export function NotFoundPage() {
  useDocumentTitle("Page not found");

  return (
    <section className="section">
      <div className="container">
        <div className="card" style={{ padding: "clamp(1.75rem, 4vw, 2.75rem)", maxWidth: 620 }}>
          <span className="pill pill--amber">404 · Route not found</span>
          <h1 style={{ fontSize: "clamp(1.75rem, 3.5vw, 2.35rem)", marginTop: "0.85rem" }}>
            This page does not exist
          </h1>
          <p style={{ marginTop: "0.65rem", color: "var(--ink-2)" }}>
            Head back to the ASHA Vaani product overview or go straight to the Android APK download page.
          </p>
          <div style={{ display: "flex", flexWrap: "wrap", gap: "0.75rem", marginTop: "1.35rem" }}>
            <Link to="/" className="btn btn--primary">
              Back to Home
            </Link>
            <Link to="/download" className="btn">
              Download for Android
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
