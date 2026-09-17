import { toast } from '../../toast.js';
import { dateLabel } from '../../format.js';

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
        toast.success(`${email} added — an invitation email has been sent`);
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
          toast.success('You have left the trip');
          location.href = 'trips.html';
          return;
        }
        toast.success(`${member.displayName} removed`);
      } catch (error) {
        toast.error(error.fullMessage);
        this.removingMember = null;
      }
    },
  };
}
