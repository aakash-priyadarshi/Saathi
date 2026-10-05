'use client';
import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import QRCode from 'qrcode';
import { BrowserQRCodeReader, type IScannerControls } from '@zxing/browser';
import { Radio, Send, Phone, Video, PhoneOff, Upload, Download, ScanLine } from 'lucide-react';
import { ErrorNotice } from '@saathi/ui';
import { NearbySession, attachmentBytes } from '../lib/nearby/session';
import { db, messages, type Message, type Attachment } from '../lib/offline/store';
import { type PeerState } from '../lib/nearby/peer';
import { useConnection } from './connectivity-provider';
type FileOffer = {
  id: string;
  name: string;
  mime: 'image/jpeg' | 'image/png' | 'image/webp' | 'text/plain';
  size: number;
  hash: string;
};
export function NearbyPage() {
  const c = useConnection(),
    session = useRef<NearbySession | null>(null),
    localVideo = useRef<HTMLVideoElement>(null),
    remoteVideo = useRef<HTMLVideoElement>(null),
    scanVideo = useRef<HTMLVideoElement>(null),
    scanner = useRef<IScannerControls | undefined>(undefined);
  const [state, setState] = useState<PeerState>('IDLE'),
    [outgoing, setOutgoing] = useState(''),
    [incoming, setIncoming] = useState(''),
    [qr, setQr] = useState(''),
    [text, setText] = useState(''),
    [history, setHistory] = useState<Message[]>([]),
    [attachments, setAttachments] = useState<Attachment[]>([]),
    [offers, setOffers] = useState<FileOffer[]>([]),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [confirmed, setConfirmed] = useState(false),
    [call, setCall] = useState<'NONE' | 'WAITING' | 'CONNECTED'>('NONE'),
    [invitation, setInvitation] = useState<{ video: boolean }>(),
    [paused, setPaused] = useState(false),
    [scanning, setScanning] = useState(false),
    [mediaAvailable, setMediaAvailable] = useState(false),
    [supported, setSupported] = useState(false),
    [peerMedia, setPeerMedia] = useState(false),
    [code, setCode] = useState('');
  const pendingCall = useRef<{ video: boolean } | undefined>(undefined);
  useEffect(() => {
    setSupported(window.isSecureContext && 'RTCPeerConnection' in window);
    setMediaAvailable(Boolean(navigator.mediaDevices?.getUserMedia));
    const value = (session.current = new NearbySession());
    const load = async () => {
      setHistory((await messages()).sort((a, b) => a.createdAt.localeCompare(b.createdAt)));
      setAttachments(await (await db()).getAll('attachments'));
      setPeerMedia(value.remoteMedia);
    };
    value.peer.onState = (next) => {
      setState(next);
      setCode(value.peer.verificationCode);
      if (next !== 'CONNECTED') {
        value.reset();
        setConfirmed(false);
        value.confirmed = false;
        setCall('NONE');
        pendingCall.current = undefined;
        setInvitation(undefined);
      }
    };
    value.onChange = () =>
      void load().catch(() =>
        setError('Nearby data could not be saved. Check this phone’s storage.'),
      );
    value.onError = setError;
    value.peer.onStream = (stream) => {
      if (remoteVideo.current) {
        remoteVideo.current.srcObject = stream;
        void remoteVideo.current.play().catch(() => {});
      }
    };
    value.peer.onQuality = setPaused;
    value.onCall = (video) => {
      if (pendingCall.current || call !== 'NONE') return;
      setInvitation({ video });
    };
    value.onAccepted = () => {
      if (!pendingCall.current) return;
      void value.peer
        .capture(pendingCall.current.video)
        .then((stream) => {
          if (localVideo.current) localVideo.current.srcObject = stream;
          setCall('CONNECTED');
        })
        .catch((e) => {
          setError(
            `Microphone or camera could not start. Check permission and try again. ${String(e)}`,
          );
          void value.peer.send('CALL_END', {});
          value.peer.stopMedia();
          setCall('NONE');
        });
    };
    value.onEnded = () => {
      pendingCall.current = undefined;
      setCall('NONE');
      setInvitation(undefined);
    };
    value.onFile = (offer) =>
      setOffers((previous) => [...previous.filter((f) => f.id !== offer.id), offer].slice(-20));
    void load().catch(() =>
      setError('Nearby messages need browser storage. Check storage permissions.'),
    );
    window.addEventListener('saathi-local-change', load);
    return () => {
      window.removeEventListener('saathi-local-change', load);
      scanner.current?.stop();
      value.peer.disconnect();
    };
  }, []);
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(
        e instanceof Error ? e.message : 'Could not connect. Check Wi-Fi and try pairing again.',
      );
    } finally {
      setBusy(false);
    }
  }
  async function showPairing(value: string) {
    setOutgoing(value);
    setQr('');
    if (value && value.length < 2400)
      try {
        setQr(await QRCode.toDataURL(value, { errorCorrectionLevel: 'L', margin: 2, width: 320 }));
      } catch {
        /* Large descriptions use file/text exchange instead. */
      }
  }
  function downloadPairing() {
    const url = URL.createObjectURL(new Blob([outgoing], { type: 'text/plain' })),
      link = document.createElement('a');
    link.href = url;
    link.download = 'saathi-pairing.txt';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function scan() {
    setScanning(true);
    await new Promise((resolve) => setTimeout(resolve, 0));
    if (!scanVideo.current) throw new Error('Could not open the scanner.');
    scanner.current = await new BrowserQRCodeReader().decodeFromVideoDevice(
      undefined,
      scanVideo.current,
      (result, _error, controls) => {
        if (result) {
          setIncoming(result.getText());
          controls.stop();
          setScanning(false);
        }
      },
    );
  }
  async function startCall(video: boolean) {
    pendingCall.current = { video };
    setCall('WAITING');
    await session.current!.peer.send('CALL', { video });
  }
  async function acceptCall() {
    const value = invitation;
    if (!value) return;
    pendingCall.current = value;
    const stream = await session.current!.peer.capture(value.video);
    if (localVideo.current) localVideo.current.srcObject = stream;
    await session.current!.peer.send('CALL_ACCEPT', {});
    setInvitation(undefined);
    setCall('CONNECTED');
  }
  async function endCall() {
    session.current!.peer.stopMedia();
    if (state === 'CONNECTED') await session.current!.peer.send('CALL_END', {});
    pendingCall.current = undefined;
    setCall('NONE');
    setInvitation(undefined);
  }
  return (
    <div className="page-wrap nearby-wrap">
      <h1>Connect with someone nearby</h1>
      <p className="page-intro">
        Messages and calls can travel directly while you stay within a reachable local connection.
      </p>
      <p className="status-notice closed">
        Phone testing preview. Both people must keep Saathi open and join the same reachable Wi-Fi
        or hotspot. Internet is not needed for a successful nearby session. This browser cannot turn
        on radios or automatically discover phones. Some networks block direct connections.
      </p>
      {!supported ? (
        <p className="status-notice closed">
          This browser cannot start nearby connections here. Use an updated browser on Saathi’s
          secure website. Saved work is still available.
        </p>
      ) : (
        <>
          <div className="nearby-layout">
            <section className="pairing-panel">
              <h2>
                {state === 'CONNECTED'
                  ? 'Connected nearby'
                  : state === 'LOST'
                    ? 'Nearby connection lost'
                    : 'Pair the two phones'}
              </h2>
              {state === 'LOST' && (
                <p>
                  Your saved messages and updates remain on this phone. Move closer, check Wi-Fi and
                  make a new invitation.
                </p>
              )}
              {state !== 'CONNECTED' ? (
                <>
                  <button
                    className="button"
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        setOutgoing('');
                        setQr('');
                        setIncoming('');
                        await showPairing(await session.current!.peer.offer());
                      })
                    }
                  >
                    <Radio size={17} />
                    Create nearby invitation
                  </button>
                  <ol className="pairing-steps">
                    <li>Join the same Wi-Fi or hotspot. Keep both phones nearby.</li>
                    <li>
                      One person creates an invitation. The other scans it or imports the pairing
                      file.
                    </li>
                    <li>The other person sends back a reply. Import it on the first phone.</li>
                    <li>Compare the short code on both phones before sharing.</li>
                  </ol>
                  {outgoing && (
                    <div className="pairing-output">
                      {qr ? (
                        <img src={qr} alt="Saathi nearby pairing code" width={320} height={320} />
                      ) : (
                        <p>
                          This invitation is too large for one scan. Use the pairing file or copy it
                          instead.
                        </p>
                      )}
                      <label>
                        Invitation or reply to share
                        <textarea
                          aria-label="Invitation or reply to share"
                          readOnly
                          value={outgoing}
                          rows={3}
                        />
                      </label>
                      <div className="connect-actions">
                        <button className="button secondary" onClick={downloadPairing}>
                          <Download size={16} />
                          Save pairing file
                        </button>
                        <button
                          className="button secondary"
                          onClick={() =>
                            void run(async () => navigator.clipboard.writeText(outgoing))
                          }
                        >
                          Copy pairing text
                        </button>
                      </div>
                      <p className="form-hint">
                        Share this only with the person you are pairing. It expires after two
                        minutes and contains local connection details.
                      </p>
                    </div>
                  )}
                  <form
                    className="stack-form pairing-input"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void run(async () => {
                        setOutgoing('');
                        setQr('');
                        await showPairing(await session.current!.peer.accept(incoming.trim()));
                      });
                    }}
                  >
                    <label>
                      Import the other person’s invitation or reply
                      <textarea
                        required
                        aria-label="Import the other person’s invitation or reply"
                        value={incoming}
                        onChange={(e) => setIncoming(e.target.value)}
                        rows={3}
                      />
                    </label>
                    <div className="connect-actions">
                      <label className="button secondary file-button">
                        <Upload size={16} />
                        Import pairing file
                        <input
                          type="file"
                          accept="text/plain,.txt"
                          onChange={(e) => {
                            const file = e.target.files?.[0];
                            if (file && file.size <= 20000) void file.text().then(setIncoming);
                            else setError('Use a pairing file below 20 KB.');
                          }}
                        />
                      </label>
                      <button
                        type="button"
                        className="button secondary"
                        disabled={!mediaAvailable || busy}
                        onClick={() => void run(scan)}
                      >
                        <ScanLine size={16} />
                        Scan pairing code
                      </button>
                      <button className="button" disabled={busy}>
                        Use invitation or reply
                      </button>
                    </div>
                  </form>
                  {scanning && (
                    <div>
                      <video ref={scanVideo} autoPlay playsInline muted className="scan-video" />
                      <button
                        className="button secondary"
                        onClick={() => {
                          scanner.current?.stop();
                          setScanning(false);
                        }}
                      >
                        Stop camera
                      </button>
                    </div>
                  )}
                  {state === 'PAIRING' && (
                    <p role="status">
                      Connecting nearby… Use the reply on the other phone to finish.
                    </p>
                  )}
                </>
              ) : (
                <>
                  <p>Compare this code with the other person:</p>
                  <strong className="pairing-code">{code}</strong>
                  <p>
                    No internet is required while this nearby connection remains available. Stay
                    within local connection range.
                  </p>
                  {!confirmed && (
                    <button
                      className="button"
                      onClick={() => {
                        session.current!.confirmed = true;
                        setConfirmed(true);
                      }}
                    >
                      The codes match
                    </button>
                  )}
                  <button className="text-link" onClick={() => session.current?.peer.disconnect()}>
                    Disconnect nearby
                  </button>
                  {confirmed && (
                    <>
                      <button
                        className="button secondary"
                        disabled={busy}
                        onClick={() => void run(() => session.current!.shareWork())}
                      >
                        Share saved relief updates and confirmations
                      </button>
                      <p className="form-hint">
                        The other phone receives original signed relief text. It does not become
                        published merely because it arrived nearby. Sharing is limited to bounded,
                        unexpired public relief events.
                      </p>
                      <button
                        className="text-link"
                        disabled={busy}
                        onClick={() => void run(() => session.current!.resendMessages())}
                      >
                        Send my unsent messages to this person
                      </button>
                    </>
                  )}
                </>
              )}
            </section>
            <section className="nearby-conversation">
              <h2>Your nearby conversation</h2>
              <p>
                {confirmed && state === 'CONNECTED'
                  ? 'Messages can reach the paired person now.'
                  : 'You can write while disconnected. Messages stay on this phone until you explicitly send them to a paired person.'}
              </p>
              <div className="message-list" aria-live="polite">
                {history.length === 0 ? (
                  <p>No messages yet.</p>
                ) : (
                  history.slice(-50).map((m) => (
                    <article key={m.id} className={`message ${m.direction.toLowerCase()}`}>
                      <p>{m.text}</p>
                      <small>
                        {m.direction === 'IN'
                          ? 'Received nearby'
                          : m.deliveredAt
                            ? 'Reached another phone'
                            : 'Saved on this phone · Waiting to be sent'}
                      </small>
                    </article>
                  ))
                )}
              </div>
              <form
                className="stack-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  void run(async () => {
                    await session.current!.sendMessage(text.trim());
                    setText('');
                  });
                }}
              >
                <label>
                  Message
                  <textarea
                    required
                    aria-label="Message"
                    maxLength={4000}
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                  />
                </label>
                <button className="button" disabled={busy || !text.trim()}>
                  <Send size={16} />
                  {confirmed && state === 'CONNECTED'
                    ? 'Send nearby message'
                    : 'Save message on this phone'}
                </button>
              </form>
              <div className="call-actions">
                <button
                  className="button secondary"
                  disabled={!confirmed || !peerMedia || !mediaAvailable || call !== 'NONE'}
                  onClick={() => void run(() => startCall(false))}
                >
                  <Phone size={16} />
                  Nearby voice call
                </button>
                <button
                  className="button secondary"
                  disabled={!confirmed || !peerMedia || !mediaAvailable || call !== 'NONE'}
                  onClick={() => void run(() => startCall(true))}
                >
                  <Video size={16} />
                  Nearby video call
                </button>
              </div>
              {invitation && (
                <div className="call-invitation" role="status">
                  <p>The paired person would like a {invitation.video ? 'video' : 'voice'} call.</p>
                  <button className="button" onClick={() => void run(acceptCall)}>
                    Accept call
                  </button>
                  <button className="button secondary" onClick={() => void run(endCall)}>
                    Decline
                  </button>
                </div>
              )}
              <div className={`call-stage ${call === 'NONE' ? 'inactive' : ''}`}>
                <video ref={remoteVideo} autoPlay playsInline aria-label="Nearby person’s call" />
                <video
                  ref={localVideo}
                  autoPlay
                  playsInline
                  muted
                  aria-label="Your camera preview"
                />
                {call !== 'NONE' && (
                  <>
                    <p role="status">
                      {call === 'WAITING'
                        ? 'Waiting for the other person to accept.'
                        : paused
                          ? 'Connection is weaker. Video is paused; voice can continue.'
                          : 'Call connected. Microphone active. Camera active only for a video call.'}
                    </p>
                    <p>No call recording or server upload. Stay within nearby connection range.</p>
                    {paused && (
                      <button
                        className="button secondary"
                        onClick={() => session.current?.peer.resumeVideo()}
                      >
                        Try video again
                      </button>
                    )}
                    <button className="button danger" onClick={() => void run(endCall)}>
                      <PhoneOff size={16} />
                      End call
                    </button>
                  </>
                )}
              </div>
              <label
                className="button secondary file-button"
                aria-disabled={!confirmed || busy || paused}
              >
                <Upload size={16} />
                Offer a small image or text file
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp,text/plain"
                  disabled={!confirmed || busy || paused}
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) void run(() => session.current!.offerFile(file));
                    e.target.value = '';
                  }}
                />
              </label>
              {!confirmed && (
                <p className="form-hint">Pair and confirm the codes before offering a file.</p>
              )}
              {confirmed && paused && (
                <p className="form-hint">
                  File sharing is waiting for a stronger nearby connection.
                </p>
              )}
              <p className="form-hint">
                Up to 1 MB. The other person chooses whether to receive it. Larger field media waits
                for a direct upload to Saathi.
              </p>
              {offers.map((offer) => (
                <div className="saved-item" key={offer.id}>
                  <p>
                    {offer.name} · {Math.ceil(offer.size / 1024)} KB
                  </p>
                  <button
                    className="text-link"
                    onClick={() => void run(() => session.current!.acceptFile(offer))}
                  >
                    Receive or resume attachment
                  </button>
                  <button
                    className="text-link"
                    onClick={() => void run(() => session.current!.cancelFile(offer.id))}
                  >
                    Cancel transfer
                  </button>
                </div>
              ))}
              {attachments
                .filter((f) => f.direction === 'OUT')
                .map((file) => (
                  <div className="saved-item" key={file.id}>
                    <p>{file.name} · Saved for sending</p>
                    <button
                      className="text-link"
                      disabled={!confirmed || busy}
                      onClick={() => void run(() => session.current!.reofferFile(file))}
                    >
                      Offer saved attachment to this person
                    </button>
                  </div>
                ))}
              {attachments
                .filter((f) => f.direction === 'IN')
                .map((file) => (
                  <div className="saved-item" key={file.id}>
                    <p>
                      {file.name} ·{' '}
                      {file.complete
                        ? 'Checked and saved on this phone'
                        : `${Object.keys(file.chunks).length} parts saved; waiting for the rest`}
                    </p>
                    {file.complete && (
                      <button
                        className="text-link"
                        onClick={() => {
                          const raw = attachmentBytes(file),
                            url = URL.createObjectURL(
                              new Blob([raw as Uint8Array<ArrayBuffer>], { type: file.mime }),
                            ),
                            link = document.createElement('a');
                          link.href = url;
                          link.download = file.name;
                          link.click();
                          setTimeout(() => URL.revokeObjectURL(url), 1000);
                        }}
                      >
                        Save attachment
                      </button>
                    )}
                  </div>
                ))}
            </section>
          </div>
        </>
      )}
      {error && <ErrorNotice message={error} />}
      <p>
        <Link href="/offline">Open saved relief work</Link> ·{' '}
        <Link href="/connectivity">What works right now?</Link>
        {c.internet && ' · Connected to the canonical website'}
      </p>
    </div>
  );
}
