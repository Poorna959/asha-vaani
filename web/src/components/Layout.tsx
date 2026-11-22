import { useEffect, useId, useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router";
import { LogoMark } from "./Logo";
import styles from "./Layout.module.css";

const NAV = [
  { to: "/#how-it-works", label: "How it works" },
  { to: "/#safety", label: "Safety" },
  { to: "/#features", label: "Features" },
  { to: "/privacy", label: "Privacy" },
];

/** Scrolls to the hash target after navigation, or to the top for new pages. */
function useScrollOnNavigate() {
  const { pathname, hash } = useLocation();
  useEffect(() => {
    if (hash) {
      const id = decodeURIComponent(hash.slice(1));
      // Wait a frame so the target section has rendered.
      const raf = requestAnimationFrame(() => {
        const el = document.getElementById(id);
        if (el) {
          el.scrollIntoView();
          el.focus({ preventScroll: true });
        }
      });
      return () => cancelAnimationFrame(raf);
    }
    window.scrollTo(0, 0);
    return undefined;
  }, [pathname, hash]);
}

export function Header() {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const location = useLocation();

  useEffect(() => setOpen(false), [location.pathname, location.hash]);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <header className={styles.header}>
      <div className={`container ${styles.headerInner}`}>
        <Link to="/" className={styles.brand} aria-label="ASHA Vaani home">
          <LogoMark size={34} />
          <span>ASHA Vaani</span>
        </Link>

        <nav aria-label="Primary" className={styles.desktopNav}>
          <ul>
            {NAV.map((item) => (
              <li key={item.to}>
                <Link to={item.to} className={styles.navLink}>
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>

        <div className={styles.headerActions}>
          <NavLink to="/download" className={`btn btn--primary btn--sm ${styles.headerCta}`}>
            <DownloadIcon /> Download
          </NavLink>
          <button
            type="button"
            className={styles.menuButton}
            aria-expanded={open}
            aria-controls={menuId}
            onClick={() => setOpen((v) => !v)}
          >
            <span className="visually-hidden">{open ? "Close menu" : "Open menu"}</span>
            <span className={styles.menuIcon} data-open={open} aria-hidden="true" />
          </button>
        </div>
      </div>

      <nav id={menuId} aria-label="Mobile" className={styles.mobileNav} data-open={open} hidden={!open}>
        <ul className="container">
          {NAV.map((item) => (
            <li key={item.to}>
              <Link to={item.to}>{item.label}</Link>
            </li>
          ))}
          <li>
            <Link to="/download" className="btn btn--primary">
              <DownloadIcon /> Download for Android
            </Link>
          </li>
        </ul>
      </nav>
    </header>
  );
}

export function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={`container ${styles.footerInner}`}>
        <div className={styles.footerBrand}>
          <Link to="/" className={styles.brand} aria-label="ASHA Vaani home">
            <LogoMark size={30} />
            <span>ASHA Vaani</span>
          </Link>
          <p>
            Voice field register for ASHA workers. Speak the home visit and the register fills itself, in Hindi,
            Kannada, Tamil, Telugu or English.
          </p>
        </div>
        <nav aria-label="Footer" className={styles.footerNav}>
          <div>
            <h2>Product</h2>
            <ul>
              <li>
                <Link to="/#how-it-works">How it works</Link>
              </li>
              <li>
                <Link to="/#safety">Danger-sign safety</Link>
              </li>
              <li>
                <Link to="/#features">Features</Link>
              </li>
            </ul>
          </div>
          <div>
            <h2>Get the app</h2>
            <ul>
              <li>
                <Link to="/download">Download for Android</Link>
              </li>
              <li>
                <Link to="/download#install">Install guide</Link>
              </li>
              <li>
                <Link to="/privacy">Permissions &amp; data</Link>
              </li>
            </ul>
          </div>
        </nav>
      </div>
      <div className={`container ${styles.footerBottom}`}>
        <p>Built for the Gnani Voice Hackathon · PS1 Healthcare.</p>
        <p>ASHA Vaani never diagnoses or recommends treatment. It only flags danger signs and asks for a PHC referral.</p>
      </div>
    </footer>
  );
}

export function DownloadIcon({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false">
      <path d="M12 4v11m0 0 4.5-4.5M12 15l-4.5-4.5M5 19h14" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function Layout() {
  useScrollOnNavigate();
  return (
    <>
      <a href="#main" className={styles.skipLink}>
        Skip to content
      </a>
      <Header />
      <main id="main" tabIndex={-1} className={styles.main}>
        <Outlet />
      </main>
      <Footer />
    </>
  );
}
