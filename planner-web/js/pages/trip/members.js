import { toast } from '../../toast.js';
import { dateLabel } from '../../format.js';
import { t } from '../../i18n/index.js';

/**
 * The Members tab.
 *
 * Any member can add or remove another; only the owner is protected, and
 * removing yourself is how you leave a trip.
 */
export function membersTab() {
  return {
    memberEmail: '',
    memberError: '',
    addingMember: false,
    removingMember: null,

    addedLabel(member) {
      return dateLabel(member.invitedAt);
    },

    async addMember() {
      const email = this.memberEmail.trim();
      if (!email) return;

      this.memberError = '';
      this.addingMember = true;
      try {
        this.members = await this.api.addMember(this.trip.id, email);
        this.memberEmail = '';
        toast.success(t('members.invited', { email }));
      } catch (error) {
        this.memberError = error.fullMessage;
      } finally {
        this.addingMember = false;
      }
    },

    /** Removing yourself leaves the trip, so the wording changes. */
    isSelf(member) {
      return member.userId === this.currentUserId;
    },

    askRemoveMember(member) {
      this.removingMember = member;
    },

    async confirmRemoveMember() {
      const member = this.removingMember;
      try {
        this.members = await this.api.removeMember(this.trip.id, member.userId);
        this.removingMember = null;
        if (this.isSelf(member)) {
          toast.success(t('members.left'));
          location.href = 'trips.html';
          return;
        }
        toast.success(t('members.removed', { name: member.displayName }));
      } catch (error) {
        toast.error(error.fullMessage);
        this.removingMember = null;
      }
    },
  };
}
