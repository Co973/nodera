import {
  ArrowDown,
  ArrowUpRight,
  Radio,
  Smartphone,
  Monitor,
  Code2,
  Fingerprint,
  Wifi,
  LockKeyhole,
} from 'lucide-react';
const repo = 'https://github.com/Co973/nodera';
export default function Home() {
  return (
    <>
      <a className="skip" href="#main">
        Skip to content
      </a>
      <header className="header wrap">
        <a className="brand" href="#main" aria-label="Nodera home">
          <Radio aria-hidden="true" />
          nodera<span>.</span>
        </a>
        <nav aria-label="Main navigation">
          <a href="#how">How it works</a>
          <a className="source-link" href={repo}>
            Source <ArrowUpRight size={16} />
          </a>
          <a className="nav-download" href="#download">
            Get the preview <ArrowDown size={16} />
          </a>
        </nav>
      </header>
      <main id="main">
        <section className="hero wrap" aria-labelledby="hero-title">
          <div>
            <p className="eyebrow">
              <i className="status-dot" /> INDEPENDENT BY DESIGN · EARLY PREVIEW
            </p>
            <h1 id="hero-title">
              Good conversations.
              <br />
              <span>Closer to home.</span>
            </h1>
            <p className="hero-description">
              Messaging that starts with the people nearby. Nodera is an
              experimental, local-first app built around direct connections,
              without a central messaging server.
            </p>
            <a className="button primary" href="#download">
              Get Nodera for Android <ArrowDown size={20} />
            </a>
            <p className="hero-note">
              Android 12+ <span>/</span> Free preview <span>/</span> No account
              required
            </p>
          </div>
          <figure
            className="network"
            aria-label="Connection diagram: two peers exchange messages over the same local network without a central messaging server."
          >
            <div className="network-top">
              <span>THE CONNECTION IS LOCAL</span>
              <Wifi size={19} />
            </div>
            <div className="network-flow">
              <div className="peer">
                <div className="peer-icon">
                  <Smartphone size={38} strokeWidth={1.4} />
                </div>
                <b>You</b>
                <small>Your device</small>
              </div>
              <div className="connection">
                <span>LAN</span>
                <div className="connection-line">
                  <i />
                  <i />
                  <i />
                </div>
                <small>Direct connection</small>
              </div>
              <div className="peer">
                <div className="peer-icon">
                  <Smartphone size={38} strokeWidth={1.4} />
                </div>
                <b>Your people</b>
                <small>A nearby peer</small>
              </div>
            </div>
            <div className="network-bottom">
              <i className="status-dot" />
              Same network. A shorter path.
            </div>
            <p className="diagram-caption">
              Connection concept · device validation still ahead
            </p>
          </figure>
        </section>
        <div className="principles wrap">
          <div>
            <Wifi />
            <span>Connect over your local network</span>
          </div>
          <div>
            <Fingerprint />
            <span>An identity on your device</span>
          </div>
          <div>
            <LockKeyhole />
            <span>Passphrase-encrypted local storage</span>
          </div>
        </div>
        <section
          id="download"
          className="downloads wrap"
          aria-labelledby="download-title"
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">01 / GET THE APP</p>
              <h2 id="download-title">Start with a preview.</h2>
            </div>
            <p>
              A small project, taking its first steps.
              <br />
              Try it out. Help shape what comes next.
            </p>
          </div>
          <div className="platforms">
            <article className="platform android">
              <div className="platform-heading">
                <Smartphone size={29} />
                <span className="badge">PREVIEW AVAILABLE</span>
              </div>
              <h3>Android</h3>
              <p>Take the early build for a spin on your phone.</p>
              <div className="release-meta">
                <span>v0.2.0 preview</span>
                <span>Android 12+</span>
                <span>2.3 MB</span>
              </div>
              <a
                className="button primary"
                href="/downloads/Nodera-0.2.0-preview.apk"
                download
              >
                Download APK <ArrowDown size={20} />
              </a>
              <a
                className="quiet-link"
                href="/downloads/SHA256SUMS.txt"
                download
              >
                Download SHA-256 checksum <ArrowUpRight size={14} />
              </a>
              <p className="small">
                The app may appear as “Mesh” on your device. This build predates
                the Nodera name.
              </p>
            </article>
            <article className="platform windows">
              <div className="platform-heading">
                <Monitor size={29} />
                <span className="badge muted">PORTABLE PREVIEW</span>
              </div>
              <h3>Windows</h3>
              <p>A desktop companion for your local network.</p>
              <div className="release-meta">
                <span>v0.2.1 preview</span>
                <span>Windows x64</span>
                <span>36 MB</span>
              </div>
              <a
                className="button primary"
                href="https://raw.githubusercontent.com/Co973/nodera/e1abf512e7412e97b36eadab6d60e661172fc286/dist/windows/Nodera-0.2.1-x64-portable.zip"
              >
                Download Windows ZIP <ArrowDown size={20} />
              </a>
              <a
                className="quiet-link"
                href="/downloads/WINDOWS-SHA256SUMS.txt"
                download
              >
                Download SHA-256 checksum <ArrowUpRight size={14} />
              </a>
              <a className="quiet-link" href={repo}>
                Explore the desktop source <ArrowUpRight size={14} />
              </a>
              <p className="small">
                Extract the entire ZIP, then open Mesh.exe. Version 0.2.1 fixes
                the startup timeout and passes the packaged startup check. Your
                existing local data is preserved. An MSI is still in
                development.
              </p>
            </article>
          </div>
          <aside className="preview-note">
            <b>Before you install</b>
            <p>
              This is experimental software. The APK is signed, but real-device
              behavior still needs validation. Encryption is unaudited and has
              no forward secrecy. Bluetooth support awaits device testing. Keep
              sensitive or safety-critical conversations on a trusted
              alternative.
            </p>
          </aside>
        </section>
        <section id="how" className="how wrap" aria-labelledby="how-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">02 / YOUR FIRST CONNECTION</p>
              <h2 id="how-title">Keep it in your circle.</h2>
            </div>
          </div>
          <ol className="steps">
            <li>
              <span className="step-number">01</span>
              <h3>Install the preview</h3>
              <p>
                Download the APK on Android 12 or newer. Open it and, if
                prompted, allow this installation from your browser or file
                manager.
              </p>
            </li>
            <li>
              <span className="step-number">02</span>
              <h3>Make yourself at home</h3>
              <p>
                Open the app, choose a display name, and create a vault
                passphrase. Keep it somewhere safe; there’s no passphrase
                recovery.
              </p>
            </li>
            <li>
              <span className="step-number">03</span>
              <h3>Find your people</h3>
              <p>
                Join the same local network. Add each other’s peer addresses and
                compare identity fingerprints in person before trying a
                conversation.
              </p>
            </li>
          </ol>
        </section>
        <section className="open-source wrap">
          <Code2 size={30} />
          <div>
            <h2>Built in the open.</h2>
            <p>Read the code, follow the work, or report what you find.</p>
          </div>
          <a className="button outline" href={repo}>
            Nodera on GitHub <ArrowUpRight size={18} />
          </a>
        </section>
      </main>
      <footer className="footer wrap">
        <a className="brand" href="#main">
          <Radio />
          nodera<span>.</span>
        </a>
        <p>Local connections. An independent experiment.</p>
        <a href={repo + '/issues'}>
          Report an issue <ArrowUpRight size={15} />
        </a>
      </footer>
    </>
  );
}
