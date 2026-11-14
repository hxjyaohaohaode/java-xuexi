import { Component, type ErrorInfo, type ReactNode } from 'react';
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('TypeForge could not render a resource', error.message, info.componentStack);
  }
  render() {
    return this.state.failed ? (
      <main className="boot">
        <h1>这个页面未能完整显示</h1>
        <p>本标签页中的未保存源稿仍保留在本地。重新读取后再继续，服务端没有收到虚假的保存确认。</p>
        <button onClick={() => location.reload()}>重新读取工作台</button>
      </main>
    ) : (
      this.props.children
    );
  }
}
