import { Component, ReactNode } from 'react'

// An error boundary. A render error in one reply (or one page) shows a small note in its place instead of React
// unmounting the whole app and leaving a blank screen.
export default class Guard extends Component<{ children: ReactNode; label?: string; inline?: boolean }, { error: Error | null }> {
  state = { error: null as Error | null }
  static getDerivedStateFromError(error: Error) { return { error } }
  componentDidCatch(error: Error) { console.error(`[Strategos] ${this.props.label ?? 'view'} failed to render:`, error) }
  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className={`guard ${this.props.inline ? 'inline' : ''}`} role="alert">
        {this.props.inline ? 'This part couldn’t be shown.' : 'Something went wrong showing this page.'}{' '}
        <button className="link" onClick={() => this.setState({ error: null })}>Try again</button>
      </div>
    )
  }
}
