import { toast } from '../../toast.js';
import { THEME_REGISTRY, savedTheme } from '../../theme-selector.js';

/**
 * The Publish tab.
 *
 * Only the owner can publish. Another member gets "Request to Publish"
 * instead, and the owner's approval is what actually publishes — so the
 * button a member sees depends on who they are, not on the trip's state.
 */
export function publishTab() {
  return {
    publishBusy: false,
    requestNote: '',
    confirmingPublish: false,
    confirmingUnpublish: false,
    copied: false,

    /**
     * The theme a publish will use: whichever one the member is looking at.
     *
     * Read at the moment of publishing rather than held in state, so switching
     * theme and publishing does the obvious thing without a picker to keep in
     * step. There used to be a select here, which asked a question the answer
     * to was already on screen — and defaulted to this same value anyway.
     *
     * Republishing later picks up whatever is current then, which is how the
     * page gets restyled: switch theme, publish again.
     */
    get themeToPublish() {
      return savedTheme();
    },

    /**
     * The staged page a pending request built. Members only, never cached.
     *
     * Linked rather than fetched: it is a whole page, and the point is to see
     * it as a reader would before approving it.
     */
    get publishPreviewUrl() {
      return this.api.publishPreviewUrl(this.trip.id);
    },

    /** "Minima Theme", for the line that says what publishing will use. */
    get themeToPublishLabel() {
      return THEME_REGISTRY[this.themeToPublish]?.labelFull || this.themeToPublish;
    },

    get publishState() {
      return this.publish?.status || 'DRAFT';
    },

    get isPublished() {
      return this.publishState === 'PUBLISHED';
    },

    get pendingRequests() {
      return (this.publish?.requests || []).filter((request) => request.status === 'PENDING');
    },

    get decidedRequests() {
      return (this.publish?.requests || []).filter((request) => request.status !== 'PENDING');
    },

    /** The signed-in member's own pending request, if any. */
    get myPendingRequest() {
      return this.pendingRequests.find((r) => r.requestedByUserId === this.currentUserId) || null;
    },

    async doPublish() {
      this.publishBusy = true;
      try {
        this.publish = await this.api.publish(this.trip.id, this.themeToPublish);
        this.trip.status = 'PUBLISHED';
        this.confirmingPublish = false;
        toast.success('Trip published');
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      } finally {
        this.publishBusy = false;
      }
    },

    async doUnpublish() {
      this.publishBusy = true;
      try {
        this.publish = await this.api.unpublish(this.trip.id);
        this.confirmingUnpublish = false;
        toast.success('Trip unpublished — the public link no longer works');
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      } finally {
        this.publishBusy = false;
      }
    },

    async requestPublish() {
      this.publishBusy = true;
      try {
        // The theme goes with it: the page is built now, in whatever theme
        // the requester is looking at.
        this.publish = await this.api.requestPublish(
          this.trip.id, this.requestNote.trim(), this.themeToPublish);
        this.requestNote = '';
        toast.success('Request sent to the trip owner');
      } catch (error) {
        toast.error(error.fullMessage);
      } finally {
        this.publishBusy = false;
      }
    },

    async cancelRequest(request) {
      try {
        this.publish = await this.api.cancelPublishRequest(this.trip.id, request.id);
        toast.success('Request withdrawn');
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    async approveRequest(request) {
      this.publishBusy = true;
      try {
        // The approving owner's own theme, not the requester's — they are the
        // one publishing it.
        this.publish = await this.api.approvePublish(this.trip.id, request.id,
          this.themeToPublish);
        toast.success('Published');
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      } finally {
        this.publishBusy = false;
      }
    },

    async rejectRequest(request) {
      try {
        this.publish = await this.api.rejectPublish(this.trip.id, request.id);
        toast.success('Request declined');
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    async copyLink() {
      const url = this.publish?.publicUrl;
      if (!url) return;
      try {
        await navigator.clipboard.writeText(url);
        this.copied = true;
        setTimeout(() => { this.copied = false; }, 2000);
      } catch {
        // Clipboard access needs a secure context and permission; select the
        // text so it can still be copied by hand.
        this.$refs.publicUrl?.focus();
        document.getSelection()?.selectAllChildren(this.$refs.publicUrl);
      }
    },

    publishedAtLabel() {
      const when = this.publish?.publishedAt;
      return when ? new Date(when).toLocaleString() : '';
    },

    requestedAtLabel(request) {
      return request.requestedAt ? new Date(request.requestedAt).toLocaleString() : '';
    },
  };
}
