"""Python 3.7+：确定性交错两个协程，验证错误实现与 ContextVar 作用域。"""
import asyncio
import contextvars
import io
import json
import unittest
from contextlib import contextmanager

TENANT = contextvars.ContextVar('tenant', default=None)


@contextmanager
def tenant_scope(tenant):
    """保存 token，正常返回或异常退出都恢复进入前的上下文。"""
    token = TENANT.set(tenant)
    try:
        yield
    finally:
        TENANT.reset(token)


async def observe(isolated):
    a_entered = asyncio.Event()
    b_entered = asyncio.Event()
    shared = {'tenant': None}

    async def a():
        with tenant_scope('A'):
            shared['tenant'] = 'A'
            a_entered.set()
            await b_entered.wait()
            return TENANT.get() if isolated else shared['tenant']

    async def b():
        await a_entered.wait()
        with tenant_scope('B'):
            shared['tenant'] = 'B'
            b_entered.set()
            return TENANT.get() if isolated else shared['tenant']

    return await asyncio.gather(a(), b())


class ContextIsolationTests(unittest.TestCase):
    def test_negative_shared_state(self):
        self.assertEqual(asyncio.run(observe(False)), ['B', 'B'])

    def test_fixed_concurrent_state(self):
        self.assertEqual(asyncio.run(observe(True)), ['A', 'B'])
        self.assertIsNone(TENANT.get())

    def test_failure_restores_outer_state(self):
        with tenant_scope('outer'):
            with self.assertRaisesRegex(RuntimeError, '受控失败'):
                with tenant_scope('inner'):
                    raise RuntimeError('受控失败')
            self.assertEqual(TENANT.get(), 'outer')
        self.assertIsNone(TENANT.get())


if __name__ == '__main__':
    log = io.StringIO()
    result = unittest.TextTestRunner(stream=log).run(unittest.defaultTestLoader.loadTestsFromTestCase(ContextIsolationTests))
    if not result.wasSuccessful():
        raise AssertionError(log.getvalue())
    print(json.dumps({'tests': result.testsRun, 'passed': True,
                      'negative': asyncio.run(observe(False)),
                      'fixed': asyncio.run(observe(True))}))
