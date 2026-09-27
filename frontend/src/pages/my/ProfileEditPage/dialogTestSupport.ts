// jsdom에는 네이티브 dialog의 열기/닫기 구현이 없다. 초점 가두기는 브라우저에서 검증한다.
Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
  configurable: true,
  value() {
    this.setAttribute('open', '');
  },
});
Object.defineProperty(HTMLDialogElement.prototype, 'close', {
  configurable: true,
  value() {
    this.removeAttribute('open');
  },
});
