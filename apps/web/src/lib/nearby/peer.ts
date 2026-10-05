import { z } from 'zod';
import { compressSync, decompressSync, strToU8, strFromU8 } from 'fflate';
import { base64, unbase64, hash } from '@saathi/protocol';
export type PeerState = 'IDLE' | 'PAIRING' | 'CONNECTED' | 'LOST';
const pairingSchema = z
  .object({
    v: z.literal(1),
    session: z.string().uuid(),
    createdAt: z.number(),
    type: z.enum(['offer', 'answer']),
    sdp: z.string().max(40000),
  })
  .strict();
const frameSchema = z
  .object({
    v: z.literal(1),
    kind: z.enum([
      'HELLO',
      'MESSAGE',
      'ACK',
      'INVENTORY',
      'NEED',
      'EVENT',
      'RECEIPT',
      'CALL',
      'CALL_ACCEPT',
      'CALL_END',
      'FILE_OFFER',
      'FILE_ACCEPT',
      'FILE_CHUNK',
      'FILE_DONE',
      'FILE_CANCEL',
    ]),
    id: z.string().uuid(),
    value: z.unknown(),
  })
  .strict();
export type Frame = z.infer<typeof frameSchema>;
export interface SaathiPeerTransport {
  state: PeerState;
  send(kind: Frame['kind'], value: unknown, id?: string): Promise<string>;
  disconnect(): void;
}
export class LocalPeer implements SaathiPeerTransport {
  state: PeerState = 'IDLE';
  session = crypto.randomUUID();
  private pc?: RTCPeerConnection;
  private channel?: RTCDataChannel;
  private audio?: RTCRtpTransceiver;
  private video?: RTCRtpTransceiver;
  private streams: MediaStream[] = [];
  private signalTimer?: ReturnType<typeof setTimeout>;
  private qualityTimer?: ReturnType<typeof setInterval>;
  verificationCode = '';
  private remoteStream = new MediaStream();
  private weakReadings = 0;
  private sendQueue = Promise.resolve();
  videoPaused = false;
  onState: (state: PeerState) => void = () => {};
  onFrame: (frame: Frame) => void = () => {};
  onStream: (stream: MediaStream) => void = () => {};
  onQuality: (paused: boolean) => void = () => {};
  private status(state: PeerState) {
    this.state = state;
    this.onState(state);
    window.dispatchEvent(new CustomEvent('saathi-nearby', { detail: state === 'CONNECTED' }));
  }
  private setup() {
    this.disconnect();
    const pc = (this.pc = new RTCPeerConnection({ iceServers: [], bundlePolicy: 'max-bundle' }));
    this.status('PAIRING');
    this.remoteStream = new MediaStream();
    pc.ontrack = ({ track }) => {
      this.remoteStream.addTrack(track);
      this.onStream(this.remoteStream);
    };
    pc.ondatachannel = ({ channel }) => this.bind(channel);
    pc.onconnectionstatechange = () => {
      if (this.pc !== pc) return;
      if (['failed', 'disconnected', 'closed'].includes(pc.connectionState)) {
        this.stopMedia();
        this.status('LOST');
      }
    };
    this.signalTimer = setTimeout(() => {
      if (this.state !== 'CONNECTED') {
        this.disconnect();
        this.status('LOST');
      }
    }, 120000);
    return pc;
  }
  private bind(channel: RTCDataChannel) {
    this.channel = channel;
    channel.bufferedAmountLowThreshold = 32768;
    channel.onopen = async () => {
      clearTimeout(this.signalTimer);
      const fingerprints = [this.pc?.localDescription?.sdp, this.pc?.remoteDescription?.sdp]
        .map((sdp) => /a=fingerprint:([^\r\n]+)/.exec(sdp ?? '')?.[1] ?? '')
        .sort();
      this.verificationCode = (await hash({ fingerprints, session: this.session }))
        .slice(0, 8)
        .toUpperCase();
      this.status('CONNECTED');
      this.audio = this.pc?.getTransceivers().find((t) => t.receiver.track.kind === 'audio');
      this.video = this.pc?.getTransceivers().find((t) => t.receiver.track.kind === 'video');
      void this.send('HELLO', {
        protocol: 1,
        maxFrame: 24000,
        media: Boolean(navigator.mediaDevices?.getUserMedia),
        files: true,
      });
    };
    channel.onclose = () => {
      if (this.channel !== channel) return;
      this.stopMedia();
      this.status('LOST');
    };
    channel.onmessage = ({ data }) => {
      if (typeof data !== 'string' || new TextEncoder().encode(data).length > 24000) return;
      try {
        this.onFrame(frameSchema.parse(JSON.parse(data)));
      } catch {
        /* Unrecognized/unbounded peer frames are ignored. */
      }
    };
  }
  private async description(type: 'offer' | 'answer') {
    const pc = this.pc!;
    await pc.setLocalDescription(
      type === 'offer' ? await pc.createOffer() : await pc.createAnswer(),
    );
    if (pc.iceGatheringState !== 'complete')
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new Error('Could not prepare nearby pairing. Check Wi-Fi and try again.')),
          12000,
        );
        pc.addEventListener('icegatheringstatechange', () => {
          if (pc.iceGatheringState === 'complete') {
            clearTimeout(timer);
            resolve();
          }
        });
      });
    const value = {
      v: 1,
      session: this.session,
      createdAt: Date.now(),
      type,
      sdp: pc.localDescription!.sdp,
    };
    return 'SAATHI1:' + base64(compressSync(strToU8(JSON.stringify(value))));
  }
  async offer() {
    const pc = this.setup();
    this.session = crypto.randomUUID();
    pc.addTransceiver('audio', { direction: 'sendrecv' });
    pc.addTransceiver('video', { direction: 'sendrecv' });
    this.bind(pc.createDataChannel('saathi-v1', { ordered: true }));
    return this.description('offer');
  }
  async accept(text: string) {
    if (!text.startsWith('SAATHI1:') || text.length > 20000)
      throw new Error('This is not a Saathi pairing invitation.');
    // The decompressor gets a fixed output bound; malicious compressed descriptions cannot expand indefinitely.
    const decoded = decompressSync(unbase64(text.slice(8)), { out: new Uint8Array(50000) });
    const value = pairingSchema.parse(JSON.parse(strFromU8(decoded).replace(/\0+$/, '')));
    if (Math.abs(Date.now() - value.createdAt) > 120000)
      throw new Error('This invitation has expired. Ask the other person to make a new one.');
    if (value.type === 'offer') {
      const pc = this.setup();
      this.session = value.session;
      await pc.setRemoteDescription({ type: 'offer', sdp: value.sdp });
      pc.getTransceivers().forEach((t) => {
        t.direction = 'sendrecv';
      });
      return this.description('answer');
    }
    if (!this.pc || value.session !== this.session || this.pc.signalingState !== 'have-local-offer')
      throw new Error('This reply belongs to a different invitation.');
    await this.pc.setRemoteDescription({ type: 'answer', sdp: value.sdp });
    return '';
  }
  async send(kind: Frame['kind'], value: unknown, id = crypto.randomUUID()) {
    const text = JSON.stringify({ v: 1, kind, value, id });
    if (new TextEncoder().encode(text).length > 24000)
      throw new Error('This nearby message is too large.');
    const send = async () => {
      const channel = this.channel;
      if (channel?.readyState !== 'open')
        throw new Error('Nearby connection lost. Your saved work stays on this phone.');
      while (channel.bufferedAmount > 65536) {
        await new Promise<void>((resolve, reject) => {
          const cleanup = () => {
            clearTimeout(timer);
            channel.removeEventListener('bufferedamountlow', done);
            channel.removeEventListener('close', closed);
          };
          const done = () => {
              cleanup();
              resolve();
            },
            closed = () => {
              cleanup();
              reject(new Error('Nearby connection lost.'));
            };
          const timer = setTimeout(() => {
            cleanup();
            reject(new Error('Nearby connection is taking too long. Try reconnecting.'));
          }, 15000);
          channel.addEventListener('bufferedamountlow', done, { once: true });
          channel.addEventListener('close', closed, { once: true });
        });
      }
      channel.send(text);
    };
    const pending = this.sendQueue.then(send);
    this.sendQueue = pending.catch(() => {});
    await pending;
    return id;
  }
  async capture(video: boolean) {
    if (this.state !== 'CONNECTED') throw new Error('Connect nearby before calling.');
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: video
        ? { width: { ideal: 640 }, height: { ideal: 360 }, frameRate: { ideal: 15, max: 20 } }
        : false,
    });
    this.streams.push(stream);
    await this.audio?.sender.replaceTrack(stream.getAudioTracks()[0] ?? null);
    await this.video?.sender.replaceTrack(stream.getVideoTracks()[0] ?? null);
    this.qualityTimer = setInterval(() => void this.adapt(), 4000);
    return stream;
  }
  async adapt() {
    if (!this.pc || !this.video?.sender.track) return;
    const stats: {
      type: string;
      state?: string;
      nominated?: boolean;
      currentRoundTripTime?: number;
      availableOutgoingBitrate?: number;
      fractionLost?: number;
    }[] = [];
    (await this.pc.getStats()).forEach((report) => stats.push(report));
    const pair = stats.find(
      (s) => s.type === 'candidate-pair' && s.state === 'succeeded' && s.nominated,
    );
    const inbound = stats.filter((s) => s.type === 'remote-inbound-rtp');
    const weak =
      (pair?.currentRoundTripTime ?? 0) > 0.8 ||
      inbound.some((s) => (s.fractionLost ?? 0) > 0.12) ||
      (pair?.availableOutgoingBitrate !== undefined && pair.availableOutgoingBitrate < 150000);
    this.weakReadings = weak ? this.weakReadings + 1 : 0;
    const parameters = this.video.sender.getParameters();
    if (parameters.encodings.length) {
      for (const encoding of parameters.encodings) {
        encoding.maxBitrate = weak ? 100000 : 500000;
        encoding.maxFramerate = weak ? 8 : 15;
        encoding.scaleResolutionDownBy = weak ? 2 : 1;
      }
      await this.video.sender.setParameters(parameters).catch(() => {});
    }
    if (this.weakReadings >= 2) this.pauseVideo();
  }
  pauseVideo() {
    if (this.video?.sender.track) this.video.sender.track.enabled = false;
    this.videoPaused = true;
    this.onQuality(true);
  }
  resumeVideo() {
    if (this.video?.sender.track) this.video.sender.track.enabled = true;
    this.videoPaused = false;
    this.weakReadings = 0;
    this.onQuality(false);
  }
  stopMedia() {
    clearInterval(this.qualityTimer);
    this.streams.forEach((s) => s.getTracks().forEach((t) => t.stop()));
    this.streams = [];
    void this.audio?.sender.replaceTrack(null).catch(() => {});
    void this.video?.sender.replaceTrack(null).catch(() => {});
  }
  disconnect() {
    clearTimeout(this.signalTimer);
    clearInterval(this.qualityTimer);
    this.stopMedia();
    const channel = this.channel,
      pc = this.pc;
    this.channel = undefined;
    this.pc = undefined;
    channel?.close();
    pc?.close();
    this.audio = undefined;
    this.video = undefined;
    this.status('IDLE');
  }
}
