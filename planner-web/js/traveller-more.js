/**
 * The "👥 +3 people" pill: a button that opens a small popup naming everyone
 * going, so a row stays narrow however many travel buddies it has.
 *
 * Hovering shows the same names as a tooltip (the button's title). The popup is
 * `position: fixed` and placed from the button's own box, because the rows it
 * sits in (tables, cards) clip anything that overflows them. It closes on a
 * click anywhere else, on Escape, when focus moves out of the pill, on scroll
 * (a fixed popup would be left floating over the wrong row) and when another
 * pill opens.
 */
const OPENED = 'traveller-more-open';
const POPUP_WIDTH = 200;

export function registerTravellerMore() {
  document.addEventListener('alpine:init', () => {
    window.Alpine.data('travellerMore', (names) => ({
      names,
      open: false,
      top: 0,
      left: 0,

      init() {
        const closeIfOther = (event) => { if (event.detail !== this.$root) this.open = false; };
        window.addEventListener(OPENED, closeIfOther);
        // Focus arriving anywhere outside the pill, even when the pill itself
        // never took focus (Safari does not focus a button on click).
        document.addEventListener('focusin', (event) => {
          if (this.open && !this.$root.contains(event.target)) this.open = false;
        });
        window.addEventListener('scroll', () => { this.open = false; }, { passive: true, capture: true });
      },

      toggle(button) {
        if (this.open) { this.open = false; return; }
        const box = button.getBoundingClientRect();
        this.top = box.bottom + 6;
        this.left = Math.max(8, Math.min(box.left, window.innerWidth - POPUP_WIDTH - 8));
        window.dispatchEvent(new CustomEvent(OPENED, { detail: this.$root }));
        this.open = true;
      },

      /** Focus left the pill (and its popup) for somewhere else. */
      focusMoved(event) {
        if (!this.$root.contains(event.relatedTarget)) this.open = false;
      },

      get popupStyle() { return `top:${this.top}px;left:${this.left}px;width:${POPUP_WIDTH}px`; },
    }));
  });
}
