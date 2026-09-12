import { toast } from '../../toast.js';
import { THEME_NAMES, THEME_REGISTRY, savedTheme } from '../../theme-selector.js';

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
    publishTheme: savedTheme(),
    requestNote: '',
    confirmingPublish: false,
    confirmingUnpublish: false,
    copied: false,

    themes: THEME_NAMES.map((name) => ({ value: name, label: THEME_REGISTRY[name].labelFull })),

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
        this.publish = await this.api.publish(this.trip.id, this.publishTheme);
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
        this.publish = await this.api.requestPublish(this.trip.id, this.requestNote.trim());
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
        this.publish = await this.api.approvePublish(this.trip.id, request.id, this.publishTheme);
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
